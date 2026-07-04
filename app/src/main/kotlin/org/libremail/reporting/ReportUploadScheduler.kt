// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.reporting

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** Enqueues a user-initiated report upload as a retrying WorkManager job and observes its state. */
@Singleton
class ReportUploadScheduler @Inject constructor(
    // A Provider (not the WorkManager itself) so WorkManager.getInstance() is resolved lazily at enqueue
    // time — never during Hilt's Application field injection, which can run before the HiltWorkerFactory
    // that on-demand WorkManager initialization needs is set. Mirrors SyncScheduler (issue #257).
    private val workManagerProvider: Provider<WorkManager>,
) {
    private val workManager get() = workManagerProvider.get()

    fun enqueue(reportId: String) {
        val request = OneTimeWorkRequestBuilder<ReportUploadWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setInputData(workDataOf(ReportUploadWorker.KEY_REPORT_ID to reportId))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        // REPLACE: a fresh Submit tap starts a clean attempt for this report, overriding any pending
        // retry-backoff. Reports are keyed per id, so distinct reports never collide.
        workManager.enqueueUniqueWork(uniqueName(reportId), ExistingWorkPolicy.REPLACE, request)
    }

    fun statusFlow(reportId: String): Flow<List<WorkInfo>> =
        workManager.getWorkInfosForUniqueWorkFlow(uniqueName(reportId))

    private fun uniqueName(reportId: String) = "$WORK_PREFIX$reportId"

    companion object {
        const val TAG = "libremail_report_upload"
        private const val WORK_PREFIX = "libremail_report_upload_"
        private const val BACKOFF_SECONDS = 30L
    }
}
