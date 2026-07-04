// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.reporting

import androidx.work.ListenableWorker.Result
import androidx.work.workDataOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

/**
 * The transmit path only runs when a `DEBUG_REPORT_ENDPOINT` is configured — the default build ships
 * an empty one (the ingest server is out of scope for this repo), so these cover the reachable control
 * flow: a missing report id, a report deleted before the job ran, and the "no endpoint configured"
 * short-circuit that returns a clear failure instead of a silent no-op.
 */
class ReportUploadWorkerTest {

    private val store = mockk<ReportStore>(relaxed = true)

    private fun worker(inputId: String?) = ReportUploadWorker(
        mockk(relaxed = true),
        mockk(relaxed = true) {
            every { inputData } returns
                if (inputId == null) workDataOf() else workDataOf(ReportUploadWorker.KEY_REPORT_ID to inputId)
        },
        store,
        endpoint = "", // default build ships no ingest endpoint; the transmit path is covered separately
    )

    private fun report(id: String) = DebugReport(
        id = id,
        createdAtMillis = 1L,
        kind = ReportKind.CRASH,
        appVersionName = "0.1.0",
        appVersionCode = 1,
        androidRelease = "14",
        androidSdkInt = 34,
        deviceManufacturer = "Google",
        deviceModel = "Pixel",
        stackTrace = "boom",
        settings = emptyMap(),
        logs = emptyList(),
    )

    @Test
    fun `a missing report id succeeds without work`() = runTest {
        assertEquals(Result.success(), worker(inputId = null).doWork())
    }

    @Test
    fun `a report discarded before the job runs succeeds without work`() = runTest {
        every { store.find("gone") } returns null

        assertEquals(Result.success(), worker(inputId = "gone").doWork())
    }

    @Test
    fun `a configured report fails cleanly when no ingest endpoint is set in this build`() = runTest {
        every { store.find("rid") } returns report("rid")

        // No endpoint configured -> a clear failure the UI can steer away from, never a silent success.
        assertEquals(Result.failure(), worker(inputId = "rid").doWork())
    }
}
