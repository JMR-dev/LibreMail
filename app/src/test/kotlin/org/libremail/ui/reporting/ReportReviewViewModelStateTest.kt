// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.reporting

import androidx.lifecycle.SavedStateHandle
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
import org.libremail.reporting.ReportSubmitter
import org.libremail.reporting.SubmitStatus
import org.libremail.ui.navigation.Routes
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Drives [ReportReviewViewModel.state] (the combine flow) and the online-submit path — the parts the
 * behaviour-focused [ReportReviewViewModelTest] leaves uncovered because it never collects `state` and
 * only exercises the no-endpoint branch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportReviewViewModelStateTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val report = DebugReport(
        id = "rid",
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
        userComment = "saved comment",
        userEmail = "saved@example.com",
    )

    private val store = mockk<ReportStore>(relaxed = true)
    private val submitter = mockk<ReportSubmitter>(relaxed = true)

    private fun viewModel(reports: List<DebugReport> = listOf(report)): ReportReviewViewModel {
        every { store.reports } returns MutableStateFlow(reports)
        every { store.find("rid") } returns reports.firstOrNull { it.id == "rid" }
        return ReportReviewViewModel(
            savedStateHandle = SavedStateHandle(mapOf(Routes.REPORT_REVIEW_ARG_ID to "rid")),
            store = store,
            submitter = submitter,
        )
    }

    @Test
    fun `state seeds from the stored report and folds edits into the preview`() = runTest(dispatcher) {
        every { submitter.isEnabled } returns true
        val vm = viewModel()

        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        val initial = vm.state.value
        assertTrue(initial.loaded)
        assertTrue(initial.exists)
        assertEquals("saved comment", initial.comment)
        assertEquals("saved@example.com", initial.email)
        assertTrue(initial.canSubmitOnline)
        assertTrue(initial.payload.contains("saved comment"))

        vm.updateComment("edited")
        vm.updateEmail("edited@example.com")
        runCurrent()

        val edited = vm.state.value
        assertEquals("edited", edited.comment)
        assertTrue(edited.payload.contains("edited@example.com"))
    }

    @Test
    fun `state marks a discarded report as no longer existing`() = runTest(dispatcher) {
        val vm = viewModel(reports = emptyList())

        backgroundScope.launch { vm.state.collect {} }
        runCurrent()

        assertTrue(vm.state.value.loaded)
        assertFalse(vm.state.value.exists)
        assertEquals("", vm.state.value.payload)
    }

    @Test
    fun `an online submit tracks the worker through to success`() = runTest(dispatcher) {
        every { submitter.isEnabled } returns true
        every { store.save(any()) } just Runs
        every { submitter.submit("rid") } just Runs
        // Covers toUi for IDLE/SUBMITTING (both -> SUBMITTING) and SUCCEEDED.
        every { submitter.status("rid") } returns
            flowOf(SubmitStatus.IDLE, SubmitStatus.SUBMITTING, SubmitStatus.SUCCEEDED)
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }
        vm.updateComment(VALID_COMMENT)
        vm.updateEmail(VALID_EMAIL)

        vm.submit()
        advanceUntilIdle()

        assertEquals(SubmitUiState.SUCCEEDED, vm.state.value.submit)
    }

    @Test
    fun `an online submit surfaces a worker failure`() = runTest(dispatcher) {
        every { submitter.isEnabled } returns true
        every { store.save(any()) } just Runs
        every { submitter.submit("rid") } just Runs
        every { submitter.status("rid") } returns flowOf(SubmitStatus.FAILED)
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }
        vm.updateComment(VALID_COMMENT)
        vm.updateEmail(VALID_EMAIL)

        vm.submit()
        advanceUntilIdle()

        assertEquals(SubmitUiState.FAILED, vm.state.value.submit)
    }

    @Test
    fun `ReportReviewState carries value semantics`() {
        val state = ReportReviewState(
            loaded = true,
            exists = true,
            payload = "p",
            comment = "c",
            email = "e@x.org",
            canSubmitOnline = true,
            submit = SubmitUiState.SUBMITTING,
        )
        assertEquals(state, state.copy())
        assertEquals(state.hashCode(), state.copy().hashCode())
        assertTrue(state.toString().contains("e@x.org"))
        assertNotEquals(state, state.copy(loaded = false))
        assertNotEquals(state, state.copy(payload = "other"))
        assertNotEquals(state, state.copy(submit = SubmitUiState.IDLE))
        assertEquals(
            listOf("IDLE", "SUBMITTING", "SUCCEEDED", "FAILED", "UNAVAILABLE"),
            SubmitUiState.entries.map { it.name },
        )
    }

    private companion object {
        val VALID_COMMENT = "a".repeat(ReportSubmissionRules.MIN_COMMENT_LENGTH)
        const val VALID_EMAIL = "reporter@example.com"
    }
}
