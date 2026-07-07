// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

/**
 * The proactive-fetch activities a debug harness can pause via [DebugFetchGate] (issue #393). Only the
 * two *proactive* activities are gateable: full-history [BACKFILL] paging ([BackfillWorker]) and the
 * post-sync body [PREFETCH] ([MailSyncer]/[MailBackfiller] `prefetchIfEnabled`). Header sync and the
 * on-demand message open are deliberately absent — they are **never** gated, so a paused gate can defer
 * background caching without ever blocking new mail arriving or a user-triggered (uncached) open. The
 * `all` wire alias ([ALL_ALIAS]) expands to every entry here.
 */
enum class FetchScope(val wireName: String) {
    BACKFILL("backfill"),
    PREFETCH("prefetch"),
    ;

    companion object {
        /** The `all` scope alias accepted on the adb wire — expands to every [FetchScope]. */
        const val ALL_ALIAS = "all"

        /**
         * Parses the comma-separated `scope` extra of the debug broadcast (e.g. `"backfill,prefetch"`
         * or `"all"`) into the set of scopes it names. Case- and whitespace-insensitive; the [ALL_ALIAS]
         * expands to every scope; unrecognised or blank tokens are ignored; a null/blank input yields
         * the empty set. Declaration order is preserved so the read-back string is stable.
         */
        fun parse(raw: String?): Set<FetchScope> {
            if (raw.isNullOrBlank()) return emptySet()
            val tokens = raw.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            if (tokens.contains(ALL_ALIAS)) return entries.toSet()
            return entries.filterTo(LinkedHashSet()) { it.wireName in tokens }
        }
    }
}

/**
 * In-memory, thread-safe holder of the currently-paused proactive-fetch [FetchScope]s — a **debug-only**
 * test hook (issue #393) that lets an adb-driven perf harness pause background body caching so a genuine
 * uncached message-open can be measured (the harness adds an account, lets headers sync, then opens a
 * message that must hit the network rather than a warmed cache).
 *
 * Lives in `src/main` so the workers/syncer/backfiller can reference it, but **every read is wrapped in
 * `if (BuildConfig.DEBUG && ...)`**. `BuildConfig.DEBUG` is a compile-time `false` in release, so R8
 * dead-code-eliminates each such branch, leaving this object unreferenced and stripping it (and
 * [FetchScope]) from the release APK entirely — verified by issue #393's release-exclusion check. The
 * writer, `FetchGateReceiver`, lives wholly in `src/debug` and is never packaged into release either;
 * this is the same source-set guarantee `ColdOpenCacheProbe` (#221) relies on.
 *
 * Defaults to **nothing paused**, so the gate is inert until a debug broadcast pauses a scope. Reads are
 * lock-free (a `@Volatile` snapshot of an immutable set, cheap enough for the fetch hot path); the rare
 * writes swap the reference under a lock.
 */
object DebugFetchGate {
    private val writeLock = Any()

    @Volatile
    private var paused: Set<FetchScope> = emptySet()

    /** Whether [scope]'s proactive fetch is currently paused. */
    fun isPaused(scope: FetchScope): Boolean = scope in paused

    /** The scopes currently paused, in [FetchScope] declaration order. */
    fun pausedScopes(): Set<FetchScope> {
        val snapshot = paused
        return FetchScope.entries.filterTo(LinkedHashSet()) { it in snapshot }
    }

    /** Pauses [scopes] (union with whatever is already paused). No-op for an empty set. */
    fun pause(scopes: Set<FetchScope>) {
        if (scopes.isEmpty()) return
        synchronized(writeLock) { paused = paused + scopes }
    }

    /** Resumes [scopes] (removes them from the paused set). No-op for an empty set. */
    fun resume(scopes: Set<FetchScope>) {
        if (scopes.isEmpty()) return
        synchronized(writeLock) { paused = paused - scopes }
    }

    /** Clears every pause, restoring the default not-paused state. Used to isolate tests. */
    fun reset() {
        synchronized(writeLock) { paused = emptySet() }
    }

    /**
     * The synchronous read-back string the debug receiver returns as ordered-broadcast result data —
     * e.g. `"paused=[backfill,prefetch]"` (declaration order) or `"paused=[]"` when nothing is paused.
     */
    fun pausedResult(): String {
        val snapshot = paused
        return FetchScope.entries.filter { it in snapshot }.joinToString(
            separator = ",",
            prefix = "paused=[",
            postfix = "]",
        ) { it.wireName }
    }
}
