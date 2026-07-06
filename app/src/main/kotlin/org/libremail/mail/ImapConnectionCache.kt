// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail

import jakarta.mail.FolderClosedException
import jakarta.mail.MessagingException
import jakarta.mail.Store
import jakarta.mail.StoreClosedException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.eclipse.angus.mail.iap.ConnectionException
import org.libremail.domain.model.ImapConnectionParams
import org.libremail.reporting.AppLog
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A per-account keep-alive cache of authenticated IMAP [Store]s, so folder-opens and message
 * operations reuse one already-connected session instead of re-paying `CONNECT + TLS + LOGIN` on
 * every call. Wiring the reuse path proven by the #125 spike; the production default is ON (see
 * `BuildConfig.IMAP_CONNECTION_REUSE`). This is the fix for the on-device finding that Gmail throttles
 * LibreMail's connect-per-operation IMAP traffic — collapsing ~one socket per operation to ~one warm
 * socket per account removes the throttle's trigger (issue #357 Part 2, `docs/perf/issue-125-*`).
 *
 * Design:
 *  - **One connection per account, mutex-guarded.** Each account key holds a single [Store] behind its
 *    own [Mutex]; every operation on that account serializes through it, so the single socket is only
 *    ever touched by one caller at a time (IMAP is serial per connection). Its known cost is
 *    head-of-line blocking — a quick flag toggle can queue behind a slow body download. A bounded pool
 *    would trade that for more sockets; that (and per-provider connection caps) is a separate effort
 *    (#356/#360-#364), deliberately NOT in scope here.
 *  - **Transparent stale-connection recovery.** No periodic `NOOP` probe (that would add a round-trip
 *    to every reused op). An operation runs optimistically; if it fails with a dropped-connection
 *    signal (server idle-timeout, NAT rebind, network change) the socket is rebuilt once and the
 *    operation retried, so the caller never sees a spurious error. A second failure clears the slot so
 *    the next call reconnects. A non-connection error (e.g. "message not found") is never retried, so a
 *    working socket is never needlessly torn down and a mutation is never re-issued over a live socket.
 *  - **Idle eviction.** [evictIdle] closes any connection unused for longer than [idleTimeoutMillis]
 *    (driven by a periodic sweep in `IdleService`), so a socket kept warm for latency doesn't linger
 *    and drain battery once the user goes idle. It skips any connection currently in use.
 *  - **Teardown.** [closeAll] evicts everything (`LOGOUT` + socket teardown); `IdleService` drives it
 *    on the low-battery push-teardown path (#88/#89/#90), mirroring the IDLE connection teardown.
 *  - **Keyed by connection identity, not the secret.** The OAuth access token
 *    ([ImapConnectionParams.secret]) rotates; keying on host/port/user/security/mechanism keeps a token
 *    refresh from orphaning a live, already-authenticated socket. A refreshed secret only matters when
 *    we actually reconnect, and [connect] is always handed the current [params].
 *
 * Coexists with IMAP IDLE: `ImapClient.idle` holds its own dedicated long-lived [Store] (not in this
 * cache), so reuse adds at most one more persistent socket per account — well under provider limits
 * (Gmail ~15).
 *
 * Thread-safety: [ImapClient]'s UI operations are not otherwise serialized and prefetch runs outside
 * the syncer's mutex, so every entry point here is safe under concurrent callers for the same account —
 * the per-key [Mutex] provides that, and [evictIdle] takes it non-blockingly so a sweep never stalls
 * behind (or interrupts) an in-flight operation.
 *
 * @param connect builds and authenticates a fresh [Store] for the given params (blocking network I/O).
 * @param idleTimeoutMillis how long a cached connection may sit unused before [evictIdle] closes it.
 * @param nowNanos monotonic clock source (injected for deterministic idle-eviction tests).
 */
internal class ImapConnectionCache(
    private val connect: (ImapConnectionParams) -> Store,
    private val idleTimeoutMillis: Long,
    private val nowNanos: () -> Long = System::nanoTime,
) {

    /**
     * One account's reused connection. [id] is an opaque per-cache ordinal used only for PII-free log
     * correlation — it is NOT derived from the host/username/secret, so a log line can attribute an
     * event to an account without ever naming it.
     */
    private class Entry(val id: Int) {
        val mutex = Mutex()

        @Volatile
        var store: Store? = null

        @Volatile
        var lastUsedAtNanos: Long = 0L
    }

    private val entries = ConcurrentHashMap<String, Entry>()
    private val nextId = AtomicInteger(0)

    /**
     * Runs [block] against a reused, authenticated [Store] for [params]'s account: established on first
     * use and kept open afterwards, so only the first call pays connection setup. Serialized per account
     * by the key's [Mutex]. Transparently reconnects once if the cached socket has been dropped. [op] is
     * a short, PII-free intent label (`body-fetch`, `backfill-page`, …) for the perf breadcrumb.
     */
    suspend fun <T> withStore(params: ImapConnectionParams, op: String, block: (Store) -> T): T {
        val entry = entries.computeIfAbsent(key(params)) { Entry(nextId.incrementAndGet()) }
        return entry.mutex.withLock { runReusing(entry, params, op, block) }
    }

    /** Establishes-or-reuses the account's [Store], runs [block], and reconnects once on a dropped socket. */
    private fun <T> runReusing(entry: Entry, params: ImapConnectionParams, op: String, block: (Store) -> T): T {
        val connectMs = ensureConnected(entry, params) // 0ms when the live connection is reused
        entry.lastUsedAtNanos = nowNanos()
        val workStart = nowNanos()
        return try {
            block(requireNotNull(entry.store))
        } catch (e: Throwable) {
            if (!isConnectionDrop(e)) throw e
            // Stale socket: rebuild once and retry so the caller never sees the drop.
            AppLog.d(TAG, "reuse stale acct=${entry.id}; reconnecting", e)
            reconnectAndRetry(entry, params, block)
        } finally {
            entry.lastUsedAtNanos = nowNanos()
            AppLog.d(PERF_TAG, "$op connect=${connectMs}ms work=${elapsedMs(workStart)}ms live=${liveCount()}")
        }
    }

    /** Reuses the live [Store] (0ms) or connects a fresh one, returning the connect cost in ms. */
    private fun ensureConnected(entry: Entry, params: ImapConnectionParams): Long {
        if (entry.store != null) {
            AppLog.d(TAG, "reuse hit acct=${entry.id}")
            return 0L
        }
        val start = nowNanos()
        entry.store = connect(params)
        val ms = elapsedMs(start)
        AppLog.d(TAG, "reuse open acct=${entry.id} connect=${ms}ms live=${liveCount()}")
        return ms
    }

    /** Closes the dropped socket, reconnects once, and retries [block]; a second failure clears the slot. */
    private fun <T> reconnectAndRetry(entry: Entry, params: ImapConnectionParams, block: (Store) -> T): T {
        runCatching { entry.store?.close() }
        entry.store = null
        entry.store = connect(params)
        entry.lastUsedAtNanos = nowNanos()
        AppLog.d(TAG, "reuse reconnected acct=${entry.id} live=${liveCount()}")
        return try {
            block(requireNotNull(entry.store))
        } catch (retry: Throwable) {
            runCatching { entry.store?.close() }
            entry.store = null
            AppLog.w(TAG, "reuse reconnect failed acct=${entry.id}", retry)
            throw retry
        }
    }

    /**
     * Closes and forgets every connection whose last use is older than [idleTimeoutMillis] (`LOGOUT` +
     * teardown). Takes each account's lock non-blockingly, so a connection currently in use is left
     * untouched and the sweep never stalls behind a slow operation. No-op when nothing is cached.
     */
    suspend fun evictIdle() {
        if (entries.isEmpty()) return
        val cutoffNanos = idleTimeoutMillis * NANOS_PER_MS
        for ((_, entry) in entries) {
            if (!entry.mutex.tryLock()) continue // in use — skip, don't interrupt or wait
            try {
                val store = entry.store
                if (store != null && nowNanos() - entry.lastUsedAtNanos >= cutoffNanos) {
                    runCatching { store.close() }
                    entry.store = null
                    AppLog.d(TAG, "reuse evict idle acct=${entry.id} live=${liveCount()}")
                }
            } finally {
                entry.mutex.unlock()
            }
        }
    }

    /** Closes and forgets every cached connection (`LOGOUT` + socket teardown). No-op when empty. */
    suspend fun closeAll() {
        if (entries.isEmpty()) return
        for ((_, entry) in entries) {
            entry.mutex.withLock {
                entry.store?.let { store ->
                    runCatching { store.close() }
                    AppLog.d(TAG, "reuse teardown acct=${entry.id}")
                }
                entry.store = null
            }
        }
        entries.clear()
    }

    /**
     * Account identity for reuse — everything that pins a distinct authenticated socket EXCEPT the
     * secret, so a rotated OAuth token reuses the same live connection instead of orphaning it.
     */
    private fun key(params: ImapConnectionParams): String =
        "${params.host}|${params.port}|${params.security}|${params.username}|${params.useXoauth2}"

    /** Count of currently-held reused sockets, for the PII-free log breadcrumb (approximate under races). */
    private fun liveCount(): Int = entries.values.count { it.store != null }

    private fun elapsedMs(startNanos: Long): Long = (nowNanos() - startNanos) / NANOS_PER_MS

    /**
     * Whether [error] signals a dropped connection (retry on a fresh socket) rather than a genuine
     * protocol/application error (propagate as-is). A server idle-timeout / NAT rebind / network change
     * surfaces as a [FolderClosedException], a [StoreClosedException], a raw [IOException] or Angus's own
     * [ConnectionException] — or, most commonly for `folder.open()` on a server-dropped socket, a plain
     * [MessagingException] *caused by* one of those ("Connection dropped by server?"). Deliberately
     * still narrow: a [MessagingException] caused by anything else (a `CommandFailedException` /
     * `BadCommandException` — a real server NO on a live connection) is NOT retried, so a working socket
     * is never needlessly torn down.
     *
     * NB (residual, tracked as the deferred mutation-idempotency review — see the #125 spike doc): the
     * retry re-runs the whole operation, so a *mutation* (flag/move/expunge) dropped mid-flight is
     * at-least-once. Flag sets are idempotent; the dominant real case — a socket the server dropped
     * while idle, detected on the next op's first command before any mutation is issued — is safe. A
     * copy-then-expunge move interrupted between its two halves is the rare exception left to that review.
     */
    private fun isConnectionDrop(error: Throwable): Boolean {
        // FolderClosedException/StoreClosedException are themselves MessagingException subtypes, so these
        // definite-drop checks must run before the MessagingException guard below — otherwise they'd fall
        // into it and get gated on a `.cause` they don't carry, instead of the unconditional `true` below.
        when {
            error is FolderClosedException || error is StoreClosedException -> return true
            error is IOException || error is ConnectionException -> return true
            error !is MessagingException -> return false
            else -> return error.cause is IOException || error.cause is ConnectionException
        }
    }

    private companion object {
        const val TAG = "ImapReuse"
        const val PERF_TAG = "ImapPerf"
        const val NANOS_PER_MS = 1_000_000L
    }
}
