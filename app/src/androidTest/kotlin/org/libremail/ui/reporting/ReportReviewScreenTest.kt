// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.reporting

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.R
import org.libremail.reporting.DebugReport
import org.libremail.reporting.ReportKind
import org.libremail.reporting.ReportStore
import org.libremail.reporting.ReportSubmitter
import org.libremail.ui.navigation.Routes
import org.libremail.ui.theme.LibreMailTheme
import java.io.File

/**
 * End-to-end UI test for [ReportReviewScreen] + [ReportReviewViewModel] over a real file-backed
 * [ReportStore] (temp dir) and a [ReportSubmitter] stubbed as disabled (no ingest endpoint, this
 * repo's default): the disclaimer + comment/email fields render, Submit stays disabled until the
 * comment reaches the minimum length and the email is valid, and Discard deletes the report and
 * leaves the screen. The submit-enqueue/upload path (WorkManager) is deliberately out of scope.
 */
@RunWith(AndroidJUnit4::class)
class ReportReviewScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val storeDir = File(context.cacheDir, "report-review-test")
    private val reportId = "r-1"

    @Before
    fun setUp() {
        storeDir.deleteRecursively()
    }

    @After
    fun tearDown() {
        storeDir.deleteRecursively()
    }

    private fun string(resId: Int) = composeTestRule.activity.getString(resId)

    private fun report(id: String) = DebugReport(
        id = id,
        createdAtMillis = 1_000L,
        kind = ReportKind.MANUAL,
        appVersionName = "1.0",
        appVersionCode = 1L,
        androidRelease = "14",
        androidSdkInt = 34,
        deviceManufacturer = "Test",
        deviceModel = "Model",
        stackTrace = null,
        settings = emptyMap(),
        logs = emptyList(),
    )

    private fun setContent(onDone: () -> Unit = {}): ReportStore {
        val store = ReportStore(storeDir)
        store.save(report(reportId))
        val submitter = mockk<ReportSubmitter> { every { isEnabled } returns false }
        val viewModel = ReportReviewViewModel(
            SavedStateHandle(mapOf(Routes.REPORT_REVIEW_ARG_ID to reportId)),
            store,
            submitter,
        )
        composeTestRule.setContent {
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                ReportReviewScreen(onDone = onDone, viewModel = viewModel)
            }
        }
        return store
    }

    @Test
    fun rendersDisclaimer_fieldsAndSubmit() {
        setContent()

        composeTestRule.onNodeWithText(string(R.string.report_pii_disclaimer_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.report_comment_label)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.report_email_label)).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.report_submit)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun submit_isDisabled_whenCommentTooShort_orEmailInvalid() {
        setContent()

        composeTestRule.onNodeWithText(string(R.string.report_submit)).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun submit_isEnabled_afterValidCommentAndEmail() {
        setContent()

        composeTestRule.onNodeWithText(string(R.string.report_comment_label))
            .performScrollTo()
            .performTextInput("x".repeat(ReportSubmissionRules.MIN_COMMENT_LENGTH))
        composeTestRule.onNodeWithText(string(R.string.report_email_label))
            .performScrollTo()
            .performTextInput("me@example.com")

        composeTestRule.onNodeWithText(string(R.string.report_submit)).performScrollTo().assertIsEnabled()
    }

    @Test
    fun tappingCopy_putsThePayloadOnTheSystemClipboard_andShowsAConfirmation() {
        // Exercises the #237 migration off LocalClipboardManager/ClipboardManager end-to-end: the
        // real system clipboard (not a fake) must contain the exact payload after the suspend
        // LocalClipboard/Clipboard call completes.
        val store = setContent()

        composeTestRule.onNodeWithText(string(R.string.report_copy)).performScrollTo().performClick()

        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText(string(R.string.report_copied)).fetchSemanticsNodes().isNotEmpty()
        }
        val clipboardManager = context.getSystemService(ClipboardManager::class.java)
        val clipText = clipboardManager?.primaryClip?.getItemAt(0)?.text?.toString()
        assertEquals(store.find(reportId)!!.toSubmissionPayload(), clipText)
    }

    @Test
    fun tappingDiscard_deletesReport_andLeavesTheScreen() {
        var done = false
        val store = setContent(onDone = { done = true })

        composeTestRule.onNodeWithText(string(R.string.report_discard)).performScrollTo().performClick()

        // Discard removes the row; the screen then auto-navigates away once it observes it is gone.
        composeTestRule.waitUntil(5_000) { store.find(reportId) == null && done }
    }
}
