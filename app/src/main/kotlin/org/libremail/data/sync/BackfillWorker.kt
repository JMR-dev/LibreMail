// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

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

    override suspend fun doWork(): Result = runCatching { backfiller.runBackfill() }.fold(
        onSuccess = { Result.success() },
        onFailure = { Result.retry() },
    )
}
