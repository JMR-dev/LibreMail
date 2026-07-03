// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.reporting

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.reporting.DebugReport
import org.libremail.reporting.ReportKind
import org.libremail.reporting.ReportStore
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class StartupReportViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun report(id: String, kind: ReportKind, createdAt: Long = 1L) = DebugReport(
        id = id,
        createdAtMillis = createdAt,
        kind = kind,
        appVersionName = "0.1.0",
        appVersionCode = 1,
        androidRelease = "14",
        androidSdkInt = 34,
        deviceManufacturer = "Google",
        deviceModel = "Pixel",
        stackTrace = null,
        settings = emptyMap(),
        logs = emptyList(),
    )

    @Test
    fun `pendingCrash surfaces the first crash report`() = runTest(dispatcher) {
        val store = mockk<ReportStore>(relaxed = true)
        every { store.reports } returns
            MutableStateFlow(listOf(report("m", ReportKind.MANUAL), report("c", ReportKind.CRASH, createdAt = 9L)))
        val vm = StartupReportViewModel(store)

        backgroundScope.launch { vm.pendingCrash.collect {} }
        runCurrent()

        assertEquals(ReportSummary("c", ReportKind.CRASH, 9L), vm.pendingCrash.value)
    }

    @Test
    fun `pendingCrash is null when only manual reports exist`() = runTest(dispatcher) {
        val store = mockk<ReportStore>(relaxed = true)
        every { store.reports } returns MutableStateFlow(listOf(report("m", ReportKind.MANUAL)))
        val vm = StartupReportViewModel(store)

        backgroundScope.launch { vm.pendingCrash.collect {} }
        runCurrent()

        assertNull(vm.pendingCrash.value)
    }

    @Test
    fun `dismiss hides the prompt for this launch without deleting the report`() = runTest(dispatcher) {
        val store = mockk<ReportStore>(relaxed = true)
        every { store.reports } returns MutableStateFlow(listOf(report("c", ReportKind.CRASH)))
        val vm = StartupReportViewModel(store)

        backgroundScope.launch { vm.pendingCrash.collect {} }
        runCurrent()
        vm.dismiss()
        runCurrent()

        assertNull(vm.pendingCrash.value)
        verify(exactly = 0) { store.delete(any()) }
    }

    @Test
    fun `discard hides the prompt and deletes the report`() = runTest(dispatcher) {
        val store = mockk<ReportStore>(relaxed = true)
        every { store.reports } returns MutableStateFlow(listOf(report("c", ReportKind.CRASH)))
        every { store.delete(any()) } just Runs
        val vm = StartupReportViewModel(store)

        backgroundScope.launch { vm.pendingCrash.collect {} }
        runCurrent()
        vm.discard("c")
        advanceUntilIdle()

        assertNull(vm.pendingCrash.value)
        verify(exactly = 1) { store.delete("c") }
    }
}
