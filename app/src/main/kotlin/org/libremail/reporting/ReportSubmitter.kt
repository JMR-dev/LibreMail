// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.reporting

import androidx.work.WorkInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.libremail.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

/** Coarse state of a user-initiated report submission. */
enum class SubmitStatus { IDLE, SUBMITTING, SUCCEEDED, FAILED }

/**
 * The single seam through which a report can leave the device, and only when the user taps Submit.
 * [isEnabled] is false when no ingest endpoint is configured (the default in this repo), so the UI
 * can steer the user to Copy/Save instead of a submission that would only fail.
 */
@Singleton
class ReportSubmitter @Inject constructor(private val scheduler: ReportUploadScheduler) {
    val isEnabled: Boolean get() = BuildConfig.DEBUG_REPORT_ENDPOINT.isNotBlank()

    fun submit(reportId: String) {
        scheduler.enqueue(reportId)
    }

    fun status(reportId: String): Flow<SubmitStatus> = scheduler.statusFlow(reportId).map(::toStatus)

    private fun toStatus(infos: List<WorkInfo>): SubmitStatus {
        if (infos.isEmpty()) return SubmitStatus.IDLE
        return when {
            infos.any { it.state == WorkInfo.State.FAILED || it.state == WorkInfo.State.CANCELLED } ->
                SubmitStatus.FAILED
            infos.all { it.state == WorkInfo.State.SUCCEEDED } -> SubmitStatus.SUCCEEDED
            else -> SubmitStatus.SUBMITTING
        }
    }
}
