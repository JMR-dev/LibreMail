// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.reporting

import androidx.work.WorkInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ReportSubmitterTest {

    private val scheduler = mockk<ReportUploadScheduler>(relaxed = true)
    private val submitter = ReportSubmitter(scheduler)

    private fun workInfo(state: WorkInfo.State) = mockk<WorkInfo> { every { this@mockk.state } returns state }

    private suspend fun status(vararg states: WorkInfo.State): SubmitStatus {
        every { scheduler.statusFlow("rid") } returns flowOf(states.map { workInfo(it) })
        return submitter.status("rid").first()
    }

    @Test
    fun `submission is disabled when no ingest endpoint is configured in this build`() {
        // The default build ships an empty DEBUG_REPORT_ENDPOINT, so nothing can ever be transmitted.
        assertFalse(submitter.isEnabled)
    }

    @Test
    fun `submit enqueues an upload for the report`() {
        submitter.submit("rid")

        verify(exactly = 1) { scheduler.enqueue("rid") }
    }

    @Test
    fun `no work yet reads as idle`() = runTest {
        assertEquals(SubmitStatus.IDLE, status())
    }

    @Test
    fun `all-succeeded work reads as succeeded`() = runTest {
        assertEquals(SubmitStatus.SUCCEEDED, status(WorkInfo.State.SUCCEEDED))
    }

    @Test
    fun `a failed or cancelled attempt reads as failed`() = runTest {
        assertEquals(SubmitStatus.FAILED, status(WorkInfo.State.FAILED))
        assertEquals(SubmitStatus.FAILED, status(WorkInfo.State.CANCELLED))
        // A failure anywhere in the set dominates even a concurrent success.
        assertEquals(SubmitStatus.FAILED, status(WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED))
    }

    @Test
    fun `in-flight or not-yet-complete work reads as submitting`() = runTest {
        assertEquals(SubmitStatus.SUBMITTING, status(WorkInfo.State.RUNNING))
        assertEquals(SubmitStatus.SUBMITTING, status(WorkInfo.State.ENQUEUED))
        // Partly done but not all succeeded is still in progress.
        assertEquals(SubmitStatus.SUBMITTING, status(WorkInfo.State.SUCCEEDED, WorkInfo.State.RUNNING))
    }

    @Test
    fun `SubmitStatus enumerates the coarse submission states`() {
        assertEquals(
            listOf("IDLE", "SUBMITTING", "SUCCEEDED", "FAILED"),
            SubmitStatus.entries.map { it.name },
        )
        assertEquals(SubmitStatus.FAILED, SubmitStatus.valueOf("FAILED"))
    }
}
