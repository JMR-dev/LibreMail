// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.reporting

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Housekeeping: deletes locally-stored crash/problem reports older than [RETENTION_DAYS] so they don't
 * accumulate on the device forever (issue #239). Scheduled to run only while charging (see
 * [org.libremail.data.sync.SyncScheduler.schedulePeriodicReportPurge]). Purely local file cleanup —
 * no network and no database, so it never blocks on the encrypted cache.
 */
@HiltWorker
class ReportPurgeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val store: ReportStore,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = runCatching {
        store.purgeOlderThan(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(RETENTION_DAYS))
    }.fold(
        onSuccess = { Result.success() },
        onFailure = { Result.retry() },
    )

    private companion object {
        const val RETENTION_DAYS = 30L
    }
}
