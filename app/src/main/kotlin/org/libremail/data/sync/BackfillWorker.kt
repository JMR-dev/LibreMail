// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import org.libremail.BuildConfig
import org.libremail.data.local.isCacheEncryptionUnavailable
import org.libremail.data.security.EncryptedCacheGuard
import org.libremail.reporting.AppLog

/**
 * Runs one bounded slice of the full-history backfill (issue #12). Cancellable (WorkManager stops it
 * on constraint loss or system pressure) and resumable — [MailBackfiller] persists its per-folder
 * boundary after every page, so the periodic schedule simply continues from where a stopped run left
 * off. Retries with WorkManager backoff on failure.
 */
@HiltWorker
class BackfillWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    // Lazy: resolving MailBackfiller builds the Room DB graph, whose first query blocks while the
    // encrypted cache is locked. Resolve it only after the cache-lock check passes, so a locked run
    // fails fast instead of parking this thread on an unsatisfiable passphrase await (mirrors SyncWorker).
    private val backfiller: Lazy<MailBackfiller>,
    private val cacheGuard: EncryptedCacheGuard,
    // Proactive pacing (#356): bounds this run's slices and cools down between them. A lightweight
    // in-process singleton (no DB graph), so it is safe to inject eagerly alongside the cache-lock gate.
    private val pacer: BackfillPacer,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        // Debug-only fetch gate (issue #393): a test harness can pause backfill via an adb broadcast so a
        // genuinely uncached message-open can be measured (proactive backfill would otherwise warm the
        // cache first). Skip-and-reschedule exactly like the cache-lock deferral below; WorkManager
        // retries and picks up from the persisted per-folder boundary once the gate resumes. The whole
        // branch is compiled out of release: BuildConfig.DEBUG is a compile-time `false` there, so R8
        // strips it (and DebugFetchGate with it).
        if (BuildConfig.DEBUG && DebugFetchGate.isPaused(FetchScope.BACKFILL)) {
            AppLog.i(TAG, "backfill deferred: fetch-gate paused")
            return Result.retry()
        }
        // Can't open the encrypted DB without the user present — retry later rather than parking a
        // WorkManager thread (which also wedges the shared serial executor) on an unsatisfiable await.
        if (cacheGuard.isCacheLocked()) {
            AppLog.i(TAG, "backfill deferred: cache locked")
            return Result.retry()
        }
        return runCatching {
            // Chain bounded slices while history remains, but PACE them (#356): a large mailbox reports
            // moreWork=true forever, so an un-paced loop would page flat-out for the whole run and keep the
            // account's IMAP session saturated (the background load that starves interactive opens, #355).
            // BackfillPacer cools down between slices and caps the slices per run, then defers to the 30-min
            // periodic cadence; isStopped ends a long run gracefully (WorkManager stop), and the cooldown is
            // a cancellable delay so a stop is never blocked. runBackfill() returns true while pages remain.
            val mailBackfiller = backfiller.get()
            pacer.runPaced(shouldContinue = { !isStopped }, slice = { mailBackfiller.runBackfill() })
        }.fold(
            onSuccess = {
                AppLog.i(TAG, "backfill worker: success")
                Result.success()
            },
            onFailure = { error ->
                if (error is CancellationException) throw error
                // A DB open that fails because SQLCipher's native library is unavailable (issue #359) lands
                // here (thrown inside the runCatching above); log it distinctly but still defer softly — a
                // later launch may load the library and recover — instead of a generic retry.
                if (error.isCacheEncryptionUnavailable()) {
                    AppLog.w(TAG, "backfill deferred: encrypted cache unavailable (SQLCipher native library)", error)
                } else {
                    AppLog.w(TAG, "backfill worker: retry", error)
                }
                Result.retry()
            },
        )
    }

    private companion object {
        const val TAG = "BackfillWorker"
    }
}
