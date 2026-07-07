// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.reporting

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.R
import org.libremail.reporting.DebugReport
import org.libremail.reporting.DiagnosticsCollector
import org.libremail.reporting.ReportKind
import org.libremail.reporting.ReportStore
import org.libremail.ui.theme.LibreMailTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric JVM port of the instrumented [ProblemReportsScreenTest] (batch 4/9 of umbrella #373):
 * drives [ProblemReportsScreen] + the real [ProblemReportsViewModel] over a mocked [ReportStore] and
 * [DiagnosticsCollector] on the JVM via the v2 `createComposeRule()` — no emulator — so
 * [ProblemReportsScreen]'s render + interaction code counts toward JaCoCo's JVM-testable surface.
 * Covers the always-present create button and auto-delete notice, the empty state, the crash/manual
 * row rendering, opening a report, and the create-report flow which saves a manual report and
 * immediately opens it for review. The instrumented [ProblemReportsScreenTest] stays as the on-device
 * E2E. See [org.libremail.ui.onboarding.AddAnotherAccountScreenJvmTest] for the pattern.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ProblemReportsScreenJvmTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun string(resId: Int) = context.getString(resId)

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

    /**
     * Builds a real [ProblemReportsViewModel] over a mocked [ReportStore] (its [ReportStore.reports]
     * seeded with [reports]) and a mocked [DiagnosticsCollector] whose `collectManual` returns
     * [manualReport] (drives the create-and-open flow), then renders [ProblemReportsScreen].
     */
    private fun setContent(
        reports: List<DebugReport>,
        manualReport: DebugReport? = null,
        onOpenReport: (String) -> Unit = {},
    ) {
        val store = mockk<ReportStore>(relaxed = true)
        every { store.reports } returns MutableStateFlow(reports)
        val collector = mockk<DiagnosticsCollector>(relaxed = true)
        if (manualReport != null) coEvery { collector.collectManual() } returns manualReport
        val viewModel = ProblemReportsViewModel(store, collector)
        composeTestRule.setContent {
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                ProblemReportsScreen(onBack = {}, onOpenReport = onOpenReport, viewModel = viewModel)
            }
        }
    }

    private fun waitForText(text: String) = composeTestRule.waitUntil(TIMEOUT_MS) {
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

        composeTestRule.waitUntil(TIMEOUT_MS) { opened == "r-crash" }
    }

    @Test
    fun tappingCreate_savesManualReport_andOpensItForReview() {
        var opened: String? = null
        setContent(
            emptyList(),
            manualReport = report("r-new", ReportKind.MANUAL),
            onOpenReport = { opened = it },
        )

        composeTestRule.onNodeWithText(string(R.string.reports_create)).performClick()

        // Creating a manual report emits its id so the screen opens it straight into review.
        composeTestRule.waitUntil(TIMEOUT_MS) { opened == "r-new" }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
