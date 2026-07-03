// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.reporting

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.libremail.reporting.DebugReport
import org.libremail.reporting.ReportKind
import org.libremail.reporting.ReportStore
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the #255 gating: the startup crash prompt surfaces a [ReportKind.CRASH] report only when it
 * is fresh (< 24h), unseen (first re-open only, persisted across relaunches), and a real crash. Uses a
 * real file-backed [ReportStore] over a temp dir so the persisted `surfaced` flag round-trips exactly
 * as it would across a process restart, and a fixed clock so the age gate is deterministic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StartupReportViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()

    private val now = 1_000_000_000_000L
    private val dayMs = 24L * 60 * 60 * 1000

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun report(id: String, kind: ReportKind, createdAt: Long) = DebugReport(
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

    private fun crash(id: String, createdAt: Long) = report(id, ReportKind.CRASH, createdAt)

    private fun store() = ReportStore(tempFolder.root)

    private fun viewModel(store: ReportStore) = StartupReportViewModel(store, now = { now })

    /** Subscribes to [StartupReportViewModel.pendingCrash] so the `WhileSubscribed` flow starts. */
    private fun TestScope.subscribe(vm: StartupReportViewModel) {
        backgroundScope.launch { vm.pendingCrash.collect {} }
        runCurrent()
    }

    @Test
    fun `surfaces a fresh unseen crash`() = runTest(dispatcher) {
        val store = store()
        store.save(crash("c", createdAt = now - 1_000L))
        val vm = viewModel(store)
        subscribe(vm)

        assertEquals(ReportSummary("c", ReportKind.CRASH, now - 1_000L), vm.pendingCrash.value)
    }

    @Test
    fun `does not surface a crash older than 24h`() = runTest(dispatcher) {
        val store = store()
        store.save(crash("old", createdAt = now - dayMs - 1))
        val vm = viewModel(store)
        subscribe(vm)

        assertNull(vm.pendingCrash.value)
    }

    @Test
    fun `surfaces a crash exactly at the 24h boundary`() = runTest(dispatcher) {
        val store = store()
        store.save(crash("edge", createdAt = now - dayMs))
        val vm = viewModel(store)
        subscribe(vm)

        assertEquals("edge", vm.pendingCrash.value?.id)
    }

    @Test
    fun `ignores non-crash reports`() = runTest(dispatcher) {
        val store = store()
        store.save(report("m", ReportKind.MANUAL, createdAt = now))
        val vm = viewModel(store)
        subscribe(vm)

        assertNull(vm.pendingCrash.value)
    }

    @Test
    fun `surfaces the newest eligible crash, skipping surfaced and stale ones`() = runTest(dispatcher) {
        val store = store()
        store.save(crash("stale", createdAt = now - dayMs - 1))
        store.save(crash("fresh", createdAt = now - 2_000L))
        store.save(crash("seen", createdAt = now - 1_000L))
        store.markSurfaced("seen")
        val vm = viewModel(store)
        subscribe(vm)

        assertEquals("fresh", vm.pendingCrash.value?.id)
    }

    @Test
    fun `dismiss marks the crash surfaced so it does not reappear on relaunch`() = runTest(dispatcher) {
        val store = store()
        store.save(crash("c", createdAt = now))
        val vm = viewModel(store)
        subscribe(vm)
        assertNotNull(vm.pendingCrash.value)

        vm.dismiss("c")
        advanceUntilIdle()

        // Hidden this launch, still stored (not deleted), and now flagged surfaced.
        assertNull(vm.pendingCrash.value)
        assertNotNull(store.find("c"))
        assertTrue(store.find("c")!!.surfaced)

        // A fresh store + VM (a new process) reads the persisted flag → never re-nags.
        val relaunchStore = store()
        val relaunchVm = viewModel(relaunchStore)
        subscribe(relaunchVm)
        assertNull(relaunchVm.pendingCrash.value)
    }

    @Test
    fun `discard deletes the report`() = runTest(dispatcher) {
        val store = store()
        store.save(crash("c", createdAt = now))
        val vm = viewModel(store)
        subscribe(vm)

        vm.discard("c")
        advanceUntilIdle()

        assertNull(vm.pendingCrash.value)
        assertNull(store.find("c"))
    }
}
