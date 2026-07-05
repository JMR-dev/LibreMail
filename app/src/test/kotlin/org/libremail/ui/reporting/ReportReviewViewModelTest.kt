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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    fun `payload shown reflects the entered email`() = runTest(testDispatcher) {
        val vm = viewModel()

        vm.updateEmail("me@example.com")

        assertEquals(report.copy(userEmail = "me@example.com").toSubmissionPayload(), vm.payload())
    }

    @Test
    fun `submit enqueues the upload exactly once and persists the reviewed comment and email`() =
        runTest(testDispatcher) {
            every { submitter.isEnabled } returns true
            every { submitter.submit("rid") } just Runs
            every { submitter.status("rid") } returns emptyFlow()
            every { store.save(any()) } just Runs
            val vm = viewModel()
            vm.updateComment(VALID_COMMENT)
            vm.updateEmail(VALID_EMAIL)

            vm.submit()

            verify(exactly = 1) {
                store.save(match { it.userComment == VALID_COMMENT && it.userEmail == VALID_EMAIL })
            }
            verify(exactly = 1) { submitter.submit("rid") }
        }

    @Test
    fun `a rapid double-tap on Submit enqueues the upload only once`() {
        // Runs on a StandardTestDispatcher so the submit coroutine is queued: the second tap lands
        // before it runs. The fix flips SUBMITTING synchronously (before the async save), so the
        // second tap is dropped instead of enqueueing a second upload (#304).
        val standardMain = StandardTestDispatcher()
        Dispatchers.setMain(standardMain)
        every { submitter.isEnabled } returns true
        every { submitter.submit("rid") } just Runs
        every { submitter.status("rid") } returns emptyFlow()
        every { store.save(any()) } just Runs
        runTest(standardMain) {
            val vm = viewModel()
            vm.updateComment(VALID_COMMENT)
            vm.updateEmail(VALID_EMAIL)

            vm.submit()
            vm.submit() // double-tap before the first submit is dispatched
            advanceUntilIdle()

            verify(exactly = 1) { submitter.submit("rid") }
            verify(exactly = 1) { store.save(any()) }
        }
    }

    @Test
    fun `submit with no endpoint configured never transmits but still persists`() = runTest(testDispatcher) {
        every { submitter.isEnabled } returns false
        every { store.save(any()) } just Runs
        val vm = viewModel()
        vm.updateComment(VALID_COMMENT)
        vm.updateEmail(VALID_EMAIL)

        vm.submit()

        // The comment/email are still persisted for Copy/Save, but nothing is enqueued for upload.
        verify(exactly = 1) {
            store.save(match { it.userComment == VALID_COMMENT && it.userEmail == VALID_EMAIL })
        }
        verify(exactly = 0) { submitter.submit(any()) }
    }

    @Test
    fun `submit does nothing when the comment is under the minimum length`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.updateEmail(VALID_EMAIL)
        vm.updateComment("way too short")

        vm.submit()

        verify(exactly = 0) { store.save(any()) }
        verify(exactly = 0) { submitter.submit(any()) }
    }

    @Test
    fun `submit does nothing when the email is blank`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.updateComment(VALID_COMMENT)

        vm.submit()

        verify(exactly = 0) { store.save(any()) }
        verify(exactly = 0) { submitter.submit(any()) }
    }

    @Test
    fun `submit does nothing when the email is malformed`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.updateComment(VALID_COMMENT)
        vm.updateEmail("not-an-email")

        vm.submit()

        verify(exactly = 0) { store.save(any()) }
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

    @Test
    fun `isCommentLongEnough is false below the threshold and true at or above it`() {
        val short = ReportReviewState(comment = "a".repeat(ReportSubmissionRules.MIN_COMMENT_LENGTH - 1))
        val exact = ReportReviewState(comment = "a".repeat(ReportSubmissionRules.MIN_COMMENT_LENGTH))

        assertFalse(short.isCommentLongEnough)
        assertTrue(exact.isCommentLongEnough)
    }

    @Test
    fun `isEmailValid accepts plausible addresses and rejects malformed or blank input`() {
        assertTrue(ReportReviewState(email = "user@example.com").isEmailValid)
        assertTrue(ReportReviewState(email = "first.last+tag@sub.example.co.uk").isEmailValid)
        assertFalse(ReportReviewState(email = "").isEmailValid)
        assertFalse(ReportReviewState(email = "no-at-sign.com").isEmailValid)
        assertFalse(ReportReviewState(email = "user@").isEmailValid)
        assertFalse(ReportReviewState(email = "user@nodot").isEmailValid)
        assertFalse(ReportReviewState(email = "@example.com").isEmailValid)
        assertFalse(ReportReviewState(email = "has space@example.com").isEmailValid)
    }

    @Test
    fun `canSubmit requires both a long-enough comment and a valid email`() {
        val validComment = "a".repeat(ReportSubmissionRules.MIN_COMMENT_LENGTH)

        assertTrue(ReportReviewState(comment = validComment, email = "user@example.com").canSubmit)
        assertFalse(ReportReviewState(comment = "short", email = "user@example.com").canSubmit)
        assertFalse(ReportReviewState(comment = validComment, email = "not-an-email").canSubmit)
        assertFalse(
            ReportReviewState(
                comment = validComment,
                email = "user@example.com",
                submit = SubmitUiState.SUBMITTING,
            ).canSubmit,
        )
    }

    private companion object {
        val VALID_COMMENT = "a".repeat(ReportSubmissionRules.MIN_COMMENT_LENGTH)
        const val VALID_EMAIL = "reporter@example.com"
    }
}
