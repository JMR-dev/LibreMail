// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.reporting

import androidx.lifecycle.SavedStateHandle
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.reporting.DebugReport
import org.libremail.reporting.ReportKind
import org.libremail.reporting.ReportStore
import org.libremail.reporting.ReportSubmitter
import org.libremail.ui.navigation.Routes
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ReportReviewViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(testDispatcher)

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
    )

    private val store = mockk<ReportStore>(relaxed = false)
    private val submitter = mockk<ReportSubmitter>()

    private fun viewModel(): ReportReviewViewModel {
        every { store.reports } returns MutableStateFlow(listOf(report))
        every { store.find("rid") } returns report
        return ReportReviewViewModel(
            savedStateHandle = SavedStateHandle(mapOf(Routes.REPORT_REVIEW_ARG_ID to "rid")),
            store = store,
            submitter = submitter,
        )
    }

    @Test
    fun `editing the comment sends nothing and saves nothing`() = runTest(testDispatcher) {
        every { submitter.isEnabled } returns true
        val vm = viewModel()

        vm.updateComment("just typing")

        verify(exactly = 0) { submitter.submit(any()) }
        verify(exactly = 0) { store.save(any()) }
    }

    @Test
    fun `payload shown is exactly what would be submitted, including the comment`() = runTest(testDispatcher) {
        val vm = viewModel()

        vm.updateComment("my note")

        assertEquals(report.copy(userComment = "my note").toSubmissionPayload(), vm.payload())
    }

    @Test
    fun `submit enqueues the upload exactly once and persists the reviewed comment`() = runTest(testDispatcher) {
        every { submitter.isEnabled } returns true
        every { submitter.submit("rid") } just Runs
        every { submitter.status("rid") } returns emptyFlow()
        every { store.save(any()) } just Runs
        val vm = viewModel()
        vm.updateComment("edited before submit")

        vm.submit()

        verify(exactly = 1) { store.save(match { it.userComment == "edited before submit" }) }
        verify(exactly = 1) { submitter.submit("rid") }
    }

    @Test
    fun `submit with no endpoint configured never transmits`() = runTest(testDispatcher) {
        every { submitter.isEnabled } returns false
        every { store.save(any()) } just Runs
        val vm = viewModel()
        vm.updateComment("please send")

        vm.submit()

        // The comment is still persisted for Copy/Save, but nothing is enqueued for upload.
        verify(exactly = 1) { store.save(match { it.userComment == "please send" }) }
        verify(exactly = 0) { submitter.submit(any()) }
    }

    @Test
    fun `discard deletes the report and does not submit`() = runTest(testDispatcher) {
        every { store.delete("rid") } just Runs
        val vm = viewModel()

        vm.discard()

        verify(exactly = 1) { store.delete("rid") }
        verify(exactly = 0) { submitter.submit(any()) }
    }
}
