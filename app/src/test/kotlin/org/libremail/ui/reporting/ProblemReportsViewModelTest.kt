// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.reporting

import app.cash.turbine.test
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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
import org.libremail.reporting.DiagnosticsCollector
import org.libremail.reporting.ReportKind
import org.libremail.reporting.ReportStore
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProblemReportsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun report(id: String, kind: ReportKind = ReportKind.MANUAL, createdAt: Long = 1L) = DebugReport(
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
    fun `reports projects each stored report to a summary row`() = runTest(dispatcher) {
        val store = mockk<ReportStore>(relaxed = true)
        every { store.reports } returns
            MutableStateFlow(listOf(report("a", ReportKind.CRASH, createdAt = 5L), report("b")))
        val vm = ProblemReportsViewModel(store, mockk(relaxed = true))

        backgroundScope.launch { vm.reports.collect {} }
        runCurrent()

        assertEquals(
            listOf(
                ReportSummary("a", ReportKind.CRASH, 5L),
                ReportSummary("b", ReportKind.MANUAL, 1L),
            ),
            vm.reports.value,
        )
    }

    @Test
    fun `createManualReport collects, saves, and emits the new report id for immediate review`() = runTest(dispatcher) {
        val store = mockk<ReportStore>(relaxed = true)
        every { store.reports } returns MutableStateFlow(emptyList())
        every { store.save(any()) } just Runs
        val collector = mockk<DiagnosticsCollector>()
        coEvery { collector.collectManual() } returns report("fresh")
        val vm = ProblemReportsViewModel(store, collector)

        vm.created.test {
            vm.createManualReport()
            advanceUntilIdle()

            assertEquals("fresh", awaitItem())
        }
        verify(exactly = 1) { store.save(match { it.id == "fresh" }) }
    }

    @Test
    fun `a rapid double-tap on Create makes only one report`() {
        // Runs on a StandardTestDispatcher so the collect coroutine is queued: the second tap lands
        // before it runs, exercising the synchronous `creating` guard so only one report is made (#304).
        val standardMain = StandardTestDispatcher()
        Dispatchers.setMain(standardMain)
        val store = mockk<ReportStore>(relaxed = true)
        every { store.reports } returns MutableStateFlow(emptyList())
        every { store.save(any()) } just Runs
        val collector = mockk<DiagnosticsCollector>()
        coEvery { collector.collectManual() } returns report("fresh")
        runTest(standardMain) {
            val vm = ProblemReportsViewModel(store, collector)

            vm.createManualReport()
            vm.createManualReport() // double-tap before the first collect runs
            advanceUntilIdle()

            coVerify(exactly = 1) { collector.collectManual() }
            verify(exactly = 1) { store.save(match { it.id == "fresh" }) }
        }
    }

    @Test
    fun `discard deletes the report from the store`() = runTest(dispatcher) {
        val store = mockk<ReportStore>(relaxed = true)
        every { store.reports } returns MutableStateFlow(emptyList())
        every { store.delete(any()) } just Runs
        val vm = ProblemReportsViewModel(store, mockk(relaxed = true))

        vm.discard("gone")
        advanceUntilIdle()

        verify(exactly = 1) { store.delete("gone") }
    }

    @Test
    fun `ReportSummary value semantics`() {
        val summary = ReportSummary("id", ReportKind.CRASH, 7L)

        val (id, kind, createdAt) = summary
        assertEquals("id", id)
        assertEquals(ReportKind.CRASH, kind)
        assertEquals(7L, createdAt)
        assertEquals(summary, summary.copy())
        assertEquals(summary.hashCode(), summary.copy().hashCode())
        assertTrue(summary.toString().contains("id"))
        assertNotEquals(summary, summary.copy(id = "other"))
        assertNotEquals(summary, summary.copy(kind = ReportKind.MANUAL))
        assertNotEquals(summary, summary.copy(createdAtMillis = 8L))
    }
}
