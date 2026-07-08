// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.reporting

import android.util.Log
import androidx.work.ListenableWorker.Result
import androidx.work.workDataOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.security.GeneralSecurityException
import kotlin.test.assertEquals

/**
 * The reachable control flow of [ReportUploadWorker] that does NOT need a live HTTP endpoint (the
 * transmit path is covered by [ReportUploadWorkerHttpTest]): a missing report id, a report deleted
 * before the job ran, and the fail-closed guards — no endpoint configured, no encryption key
 * configured, and a sealing failure — each of which returns a clear failure and sends nothing.
 * `android.util.Log` is a no-op stub in JVM tests, so it is statically mocked (the worker logs its
 * lifecycle through [AppLog]).
 */
class ReportUploadWorkerTest {

    private val store = mockk<ReportStore>(relaxed = true)

    @Before
    fun mockLog() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun unmock() = unmockkAll()

    private fun worker(
        inputId: String?,
        endpoint: String = "",
        encryptor: ReportPayloadEncryptor = ReportPayloadEncryptor.Disabled,
        anonymizer: ReportAnonymizer = ReportAnonymizer(),
    ) = ReportUploadWorker(
        mockk(relaxed = true),
        mockk(relaxed = true) {
            every { inputData } returns
                if (inputId == null) workDataOf() else workDataOf(ReportUploadWorker.KEY_REPORT_ID to inputId)
        },
        store,
        anonymizer,
        encryptor,
        endpoint = endpoint,
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

    @Test
    fun `fails closed when an endpoint is set but no encryption key is configured`() = runTest {
        every { store.find("rid") } returns report("rid")

        // Endpoint present, encryptor Disabled -> refuse to send rather than transmit unencrypted.
        val result = worker(inputId = "rid", endpoint = "https://ingest.invalid/report").doWork()

        assertEquals(Result.failure(), result)
    }

    @Test
    fun `fails closed when sealing the payload throws`() = runTest {
        every { store.find("rid") } returns report("rid")
        val anonymizer = mockk<ReportAnonymizer> {
            every { anonymize(any()) } returns report("rid")
            every { hasResidualPii(any()) } returns true // also exercises the residual-PII warning path
        }
        val encryptor = mockk<ReportPayloadEncryptor> {
            every { isConfigured() } returns true
            every { encrypt(any()) } throws GeneralSecurityException("no")
        }

        val result = worker(
            inputId = "rid",
            endpoint = "https://ingest.invalid/report",
            encryptor = encryptor,
            anonymizer = anonymizer,
        ).doWork()

        assertEquals(Result.failure(), result)
    }
}
