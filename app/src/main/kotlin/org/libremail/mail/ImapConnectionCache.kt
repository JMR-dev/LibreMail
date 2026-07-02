// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail

import jakarta.mail.FolderClosedException
import jakarta.mail.MessagingException
import jakarta.mail.Store
import jakarta.mail.StoreClosedException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.libremail.domain.model.ImapConnectionParams
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * SPIKE (issue #125): a per-account keep-alive cache of authenticated IMAP [Store]s, so folder-opens
 * and message operations reuse one already-connected session instead of re-paying
 * `CONNECT + TLS + LOGIN` on every call. See `docs/perf/issue-125-connection-reuse-spike.md`.
 *
 * Prototype stance — deliberately the simplest thing that *proves reuse*, leaving the tuning knobs to a
 * measured follow-up:
 *  - **One connection per account, mutex-guarded.** Each account key holds a single [Store] behind its
 *    own [Mutex]; every operation on that account serializes through it. This is the simplest safe
 *    design and the one the investigation named as the starting point. Its known cost is head-of-line
 *    blocking — a quick flag toggle can queue behind a slow body download. A bounded pool would trade
 *    that for more sockets (and a size cap + eviction); not prototyped here.
 *  - **Lazy, catch-and-retry-once stale handling.** No periodic `NOOP` probe (that would add a
 *    round-trip to every reused op, partly defeating the point). An operation runs optimistically; if
 *    it fails with a dropped-connection signal, the socket is rebuilt once and the operation retried.
 *  - **Keyed by connection identity, not the secret.** The OAuth access token
 *    ([ImapConnectionParams.secret]) rotates; keying on host/port/user/security/mechanism keeps a token
 *    refresh from orphaning a live, already-authenticated socket. A refreshed secret only matters when
 *    we actually reconnect, and [connect] is always handed the current [params].
 *
 * Thread-safety: [ImapClient]'s UI operations are not otherwise serialized and prefetch runs outside
 * the syncer's mutex, so [withStore] must be safe under concurrent callers for the same account — the
 * per-key mutex provides that. Not wired to any lifecycle/battery signal yet: [closeAll] is the only
 * eviction and is driven by the harness today; an idle-eviction timer and low-battery teardown
 * (#88/#89/#90) are follow-ups.
 *
 * @param connect builds and authenticates a fresh [Store] for the given params (blocking network I/O).
 */
internal class ImapConnectionCache(private val connect: (ImapConnectionParams) -> Store) {

    private class Entry {
        val mutex = Mutex()
        var store: Store? = null
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    /**
     * Runs [block] against a reused, authenticated [Store] for [params]'s account: it is established on
     * first use and kept open afterwards, so only the first call pays connection setup. Serialized per
     * account by the key's [Mutex]. If the operation hits a dropped connection the socket is rebuilt
     * once and the operation retried; a second failure clears the slot so the next call reconnects.
     */
    suspend fun <T> withStore(params: ImapConnectionParams, block: (Store) -> T): T {
        val entry = entries.computeIfAbsent(key(params)) { Entry() }
        return entry.mutex.withLock {
            val store = entry.store ?: connect(params).also { entry.store = it }
            try {
                block(store)
            } catch (e: Throwable) {
                if (!isConnectionDrop(e)) throw e
                // Stale socket (server idle-timeout, NAT rebind, network change): rebuild once and retry.
                runCatching { store.close() }
                // Forget the dead socket before reconnecting, so a failed connect leaves a clean slot.
                entry.store = null
                val fresh = connect(params)
                entry.store = fresh
                try {
                    block(fresh)
                } catch (retry: Throwable) {
                    runCatching { fresh.close() }
                    entry.store = null
                    throw retry
                }
            }
        }
    }

    /** Closes and forgets every cached connection (`LOGOUT` + socket teardown). The only eviction today. */
    suspend fun closeAll() {
        for ((_, entry) in entries) {
            entry.mutex.withLock {
                entry.store?.let { store -> runCatching { store.close() } }
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

    /**
     * Whether [error] signals a dropped connection (retry on a fresh socket) rather than a genuine
     * protocol/application error (propagate as-is). Deliberately narrow: a plain [MessagingException]
     * for a real server error whose connection is still live is NOT retried, so we never re-issue a
     * mutation over a working connection.
     */
    private fun isConnectionDrop(error: Throwable): Boolean = when (error) {
        is FolderClosedException, is StoreClosedException, is IOException -> true
        is MessagingException -> error.cause is IOException
        else -> false
    }
}
