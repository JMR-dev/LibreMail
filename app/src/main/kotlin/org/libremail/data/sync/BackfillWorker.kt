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
            // Chain bounded slices back-to-back while history remains, so a large mailbox isn't limited
            // to one slice per periodic run. runBackfill() returns true while any folder still has pages
            // left; isStopped lets WorkManager end a long run gracefully (the periodic schedule resumes).
            val mailBackfiller = backfiller.get()
            while (mailBackfiller.runBackfill() && !isStopped) { /* page the next slice */ }
        }.fold(
            onSuccess = {
                AppLog.i(TAG, "backfill worker: success")
                Result.success()
            },
            onFailure = { error ->
                if (error is CancellationException) throw error
                AppLog.w(TAG, "backfill worker: retry", error)
                Result.retry()
            },
        )
    }

    private companion object {
        const val TAG = "BackfillWorker"
    }
}
