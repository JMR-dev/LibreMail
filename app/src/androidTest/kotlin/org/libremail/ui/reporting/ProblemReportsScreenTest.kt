// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.reporting

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.R
import org.libremail.data.settings.SettingsRepository
import org.libremail.domain.repository.AccountRepository
import org.libremail.reporting.AppVersionProvider
import org.libremail.reporting.DebugReport
import org.libremail.reporting.DiagnosticsCollector
import org.libremail.reporting.ReportKind
import org.libremail.reporting.ReportStore
import org.libremail.reporting.RingLogBuffer
import org.libremail.ui.theme.LibreMailTheme
import java.io.File

/**
 * End-to-end UI test for [ProblemReportsScreen] + [ProblemReportsViewModel] backed by a real
 * file-backed [ReportStore] (temp dir) and a real [DiagnosticsCollector]: the always-present create
 * button and auto-delete notice, the empty state, the crash/manual row rendering, opening a report,
 * and the create-report flow which saves a manual report and immediately opens it for review.
 */
@RunWith(AndroidJUnit4::class)
class ProblemReportsScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val storeDir = File(context.cacheDir, "problem-reports-test")

    @Before
    fun setUp() {
        storeDir.deleteRecursively()
    }

    @After
    fun tearDown() {
        storeDir.deleteRecursively()
    }

    private fun string(resId: Int) = composeTestRule.activity.getString(resId)

    private fun report(id: String, kind: ReportKind) = DebugReport(
        id = id,
        createdAtMillis = 1_000L,
        kind = kind,
        appVersionName = "1.0",
        appVersionCode = 1L,
        androidRelease = "14",
        androidSdkInt = 34,
        deviceManufacturer = "Test",
        deviceModel = "Model",
        stackTrace = if (kind == ReportKind.CRASH) "boom" else null,
        settings = emptyMap(),
        logs = emptyList(),
    )

    private fun setContent(reports: List<DebugReport>, onOpenReport: (String) -> Unit = {}) {
        val store = ReportStore(storeDir)
        reports.forEach(store::save)
        val collector = DiagnosticsCollector(
            AppVersionProvider(context),
            SettingsRepository(context),
            mockk<AccountRepository> { every { observeAccounts() } returns flowOf(emptyList()) },
            RingLogBuffer(),
        )
        val viewModel = ProblemReportsViewModel(store, collector)
        composeTestRule.setContent {
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                ProblemReportsScreen(onBack = {}, onOpenReport = onOpenReport, viewModel = viewModel)
            }
        }
    }

    private fun waitForText(text: String) = composeTestRule.waitUntil(5_000) {
        composeTestRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun noReports_showsEmptyState_withCreateButtonAndAutoDeleteNotice() {
        setContent(emptyList())

        composeTestRule.onNodeWithText(string(R.string.reports_empty)).assertIsDisplayed()
        // The create control and the retention notice are always visible, even with no reports.
        composeTestRule.onNodeWithText(string(R.string.reports_create)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.report_auto_delete_notice)).assertIsDisplayed()
    }

    @Test
    fun storedReports_showCrashAndManualKindLabels() {
        setContent(listOf(report("r-crash", ReportKind.CRASH), report("r-manual", ReportKind.MANUAL)))
        waitForText(string(R.string.report_kind_crash))

        composeTestRule.onNodeWithText(string(R.string.report_kind_crash)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.report_kind_manual)).assertIsDisplayed()
    }

    @Test
    fun tappingReportRow_invokesOnOpenReport() {
        var opened: String? = null
        setContent(listOf(report("r-crash", ReportKind.CRASH)), onOpenReport = { opened = it })
        waitForText(string(R.string.report_kind_crash))

        composeTestRule.onNodeWithText(string(R.string.report_kind_crash)).performClick()

        composeTestRule.waitUntil(5_000) { opened == "r-crash" }
    }

    @Test
    fun tappingCreate_savesManualReport_andOpensItForReview() {
        var opened: String? = null
        setContent(emptyList(), onOpenReport = { opened = it })

        composeTestRule.onNodeWithText(string(R.string.reports_create)).performClick()

        // Creating a manual report emits its id so the screen opens it straight into review.
        composeTestRule.waitUntil(5_000) { opened != null }
    }
}
