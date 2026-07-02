// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

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
    private val backfiller: MailBackfiller,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = runCatching {
        // Chain bounded slices back-to-back while history remains, so a large mailbox isn't limited to
        // one slice per periodic run. runBackfill() returns true while any folder still has pages left;
        // isStopped lets WorkManager end a long run gracefully (the periodic schedule resumes it).
        while (backfiller.runBackfill() && !isStopped) { /* page the next slice */ }
    }.fold(
        onSuccess = { Result.success() },
        onFailure = { error -> if (error is CancellationException) throw error else Result.retry() },
    )
}
