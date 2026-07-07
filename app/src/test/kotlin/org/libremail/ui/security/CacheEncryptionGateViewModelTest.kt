// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.security

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.data.local.CacheEncryptionUnavailableException
import org.libremail.data.local.CacheOpenMode
import org.libremail.data.local.DatabaseProvisioner
import org.libremail.reporting.DebugReport
import org.libremail.reporting.DiagnosticsCollector
import org.libremail.reporting.ReportKind
import kotlin.test.assertEquals

/**
 * Unit coverage for the fail-closed encrypted-cache gate (issue #359). Pins the gate's two security
 * outcomes — that a normal start reaches [CacheEncryptionGateState.Ready] and that a
 * [CacheEncryptionUnavailableException] from the provisioner resolves to
 * [CacheEncryptionGateState.Unavailable] WITHOUT rethrowing/crashing — plus that the report it offers is
 * ephemeral: assembled from the existing [DiagnosticsCollector] and never routed through a [ReportStore]
 * (the VM has no such dependency to persist through).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CacheEncryptionGateViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        provisioner: DatabaseProvisioner,
        diagnostics: DiagnosticsCollector = mockk(relaxed = true),
    ) = CacheEncryptionGateViewModel(provisioner, diagnostics).also { it.ioDispatcher = dispatcher }

    private fun debugReport() = DebugReport(
        id = "gate-report",
        createdAtMillis = 1L,
        kind = ReportKind.MANUAL,
        appVersionName = "0.1.0",
        appVersionCode = 1,
        androidRelease = "14",
        androidSdkInt = 34,
        deviceManufacturer = "Google",
        deviceModel = "Pixel",
        stackTrace = null,
        settings = mapOf("encryptCache" to "true"),
        logs = listOf("W/DatabaseProvisioner: SQLCipher native library failed to load"),
    )

    @Test
    fun `the gate reaches Ready when the cache can be opened`() = runTest(dispatcher) {
        val provisioner = mockk<DatabaseProvisioner>()
        coEvery { provisioner.prepareCache() } returns CacheOpenMode.Plaintext

        val vm = viewModel(provisioner)

        assertEquals(CacheEncryptionGateState.Ready, vm.state.value)
    }

    @Test
    fun `the gate fails closed to Unavailable when the encrypted cache cannot be opened`() = runTest(dispatcher) {
        // The probe logs the failure via AppLog.w -> android.util.Log (a no-op stub in JVM tests); mock it
        // (fully-qualified — a raw android.util.Log import is detekt-forbidden, epic #324).
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>(), any()) } returns 0
        try {
            val provisioner = mockk<DatabaseProvisioner>()
            coEvery { provisioner.prepareCache() } throws
                CacheEncryptionUnavailableException(UnsatisfiedLinkError("dlopen failed: libsqlcipher.so"))

            val vm = viewModel(provisioner)

            // Fail closed: the error gate is shown (never the mailbox), and the VM did NOT rethrow/crash.
            assertEquals(CacheEncryptionGateState.Unavailable, vm.state.value)
        } finally {
            unmockkStatic(android.util.Log::class)
        }
    }

    @Test
    fun `prepareReport builds an ephemeral payload from the diagnostics collector, never persisting`() =
        runTest(dispatcher) {
            val provisioner = mockk<DatabaseProvisioner>()
            coEvery { provisioner.prepareCache() } returns CacheOpenMode.Plaintext
            val diagnostics = mockk<DiagnosticsCollector>()
            val report = debugReport()
            coEvery { diagnostics.collectManual() } returns report

            val vm = viewModel(provisioner, diagnostics)
            vm.prepareReport()
            advanceUntilIdle()

            // The payload is exactly the reused DiagnosticsCollector rendering — and this VM depends on no
            // ReportStore, so it structurally cannot write the report to disk (ephemeral by construction).
            assertEquals(report.toSubmissionPayload(), vm.reportPayload.value)
            coVerify(exactly = 1) { diagnostics.collectManual() }
        }

    @Test
    fun `prepareReport is idempotent while a report is already prepared`() = runTest(dispatcher) {
        val provisioner = mockk<DatabaseProvisioner>()
        coEvery { provisioner.prepareCache() } returns CacheOpenMode.Plaintext
        val diagnostics = mockk<DiagnosticsCollector>()
        coEvery { diagnostics.collectManual() } returns debugReport()

        val vm = viewModel(provisioner, diagnostics)
        vm.prepareReport()
        advanceUntilIdle()
        vm.prepareReport() // second tap while one is already held
        advanceUntilIdle()

        coVerify(exactly = 1) { diagnostics.collectManual() }
    }
}
