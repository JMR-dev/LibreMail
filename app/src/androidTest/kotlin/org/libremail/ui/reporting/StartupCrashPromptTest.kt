// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.reporting

import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.R
import org.libremail.reporting.DebugReport
import org.libremail.reporting.ReportKind
import org.libremail.reporting.ReportStore
import org.libremail.ui.StartupCrashPrompt
import org.libremail.ui.theme.LibreMailTheme
import java.io.File

/**
 * E2E for the #255 startup-crash-prompt gating, driving the real [StartupCrashPrompt] composable over a
 * real file-backed [ReportStore]: a legitimate recent crash pops the dialog exactly once (and never
 * again after a simulated relaunch reads the persisted `surfaced` flag), while a stale (> 24h) crash
 * never pops it. A fixed clock keeps the age gate independent of the device wall clock.
 */
@RunWith(AndroidJUnit4::class)
class StartupCrashPromptTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val now = 1_000_000_000_000L
    private val dayMs = 24L * 60 * 60 * 1000
    private lateinit var dir: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        dir = File(context.cacheDir, "startup_crash_prompt_test_${System.nanoTime()}")
        dir.deleteRecursively()
        dir.mkdirs()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun string(resId: Int) = composeTestRule.activity.getString(resId)

    private fun store() = ReportStore(dir)

    private fun crash(id: String, createdAt: Long) = DebugReport(
        id = id,
        createdAtMillis = createdAt,
        kind = ReportKind.CRASH,
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

    private fun viewModel(store: ReportStore) = StartupReportViewModel(store, now = { now })

    /** Renders the prompt against [vmState]; swapping its value simulates a fresh process on relaunch. */
    private fun render(vmState: MutableState<StartupReportViewModel>) {
        composeTestRule.setContent {
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                StartupCrashPrompt(viewModel = vmState.value, onReview = {})
            }
        }
    }

    private fun awaitDialogShown() = composeTestRule.waitUntil(WAIT_MS) {
        composeTestRule.onAllNodesWithText(string(R.string.crash_prompt_title)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun awaitDialogGone() = composeTestRule.waitUntil(WAIT_MS) {
        composeTestRule.onAllNodesWithText(string(R.string.crash_prompt_title)).fetchSemanticsNodes().isEmpty()
    }

    @Test
    fun recentCrash_popsDialogOnce_andNotAgainOnRelaunch() {
        val store = store()
        store.save(crash("c", createdAt = now - 60_000L))
        val vmState = mutableStateOf(viewModel(store))
        render(vmState)

        // First re-open after the crash: the prompt is offered.
        awaitDialogShown()
        composeTestRule.onNodeWithText(string(R.string.crash_prompt_title)).assertIsDisplayed()

        // "Not now" hides it and persistently marks it surfaced (the report itself stays saved).
        composeTestRule.onNodeWithText(string(R.string.crash_prompt_later)).performClick()
        awaitDialogGone()
        assertNotNull(store().find("c"))

        // Relaunch: a fresh store + VM over the same dir reads the persisted flag → no re-nag.
        composeTestRule.runOnUiThread { vmState.value = viewModel(store()) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(string(R.string.crash_prompt_title)).assertDoesNotExist()
    }

    @Test
    fun staleCrash_doesNotPopDialog() {
        val store = store()
        store.save(crash("old", createdAt = now - dayMs - 60_000L))
        render(mutableStateOf(viewModel(store)))

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(string(R.string.crash_prompt_title)).assertDoesNotExist()
    }

    @Test
    fun discard_deletesTheReport() {
        val store = store()
        store.save(crash("c", createdAt = now - 60_000L))
        render(mutableStateOf(viewModel(store)))

        awaitDialogShown()
        composeTestRule.onNodeWithText(string(R.string.crash_prompt_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.crash_prompt_discard)).performClick()
        awaitDialogGone()

        assertNull(store().find("c"))
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
