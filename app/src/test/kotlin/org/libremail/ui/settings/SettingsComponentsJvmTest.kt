// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.R
import org.libremail.ui.theme.LibreMailTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric JVM Compose tests for the shared settings row/section composables in
 * [SettingsComponents] (umbrella #373, batch #380): [SectionHeader], [SwitchRow], [ClickRow],
 * [RadioRow] and the device-only [RetentionSection]. Each stateless piece is driven directly and its
 * render + interaction/branch logic asserted deterministically on the JVM under [RobolectricTestRunner]
 * via the v2 `createComposeRule()` — no emulator — so `SettingsComponents.kt` counts toward JaCoCo's
 * JVM-testable surface. The instrumented `SettingsScreenTest`/`AccountSettingsScreenTest` stay as the
 * on-device E2E. See [org.libremail.ui.onboarding.AddAnotherAccountScreenJvmTest] for the pattern.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class SettingsComponentsJvmTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun string(resId: Int): String = context.getString(resId)

    /** Renders [content] inside the app theme and a scroll container so tall sections can `performScrollTo`. */
    private fun setContent(content: @Composable () -> Unit) {
        composeTestRule.setContent {
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) { content() }
            }
        }
    }

    @Test
    fun sectionHeader_rendersItsText() {
        setContent { SectionHeader("Downloading") }

        composeTestRule.onNodeWithText("Downloading").assertIsDisplayed()
    }

    @Test
    fun switchRow_offWithSubtitle_rowClickTogglesOn() {
        var reported: Boolean? = null
        setContent {
            SwitchRow(
                title = "Dynamic color",
                checked = false,
                onCheckedChange = { reported = it },
                subtitle = "Use wallpaper",
            )
        }

        composeTestRule.onNodeWithText("Dynamic color").assertIsDisplayed()
        composeTestRule.onNodeWithText("Use wallpaper").assertIsDisplayed()

        composeTestRule.onNodeWithText("Dynamic color").performClick()
        assertEquals(true, reported)
    }

    @Test
    fun switchRow_onWithoutSubtitle_rowClickTogglesOff() {
        var reported: Boolean? = null
        setContent { SwitchRow(title = "Push", checked = true, onCheckedChange = { reported = it }) }

        composeTestRule.onNodeWithText("Push").performClick()
        assertEquals(false, reported)
    }

    @Test
    fun clickRow_withSubtitle_invokesOnClick() {
        var clicked = false
        setContent { ClickRow(title = "Add account", onClick = { clicked = true }, subtitle = "Another mailbox") }

        composeTestRule.onNodeWithText("Another mailbox").assertIsDisplayed()
        composeTestRule.onNodeWithText("Add account").performClick()
        assertTrue(clicked)
    }

    @Test
    fun clickRow_disabled_rendersAsNotEnabled() {
        setContent { ClickRow(title = "Remove", onClick = {}, enabled = false) }

        // enabled = false marks the row disabled (no click semantics action) — the `enabled` branch.
        composeTestRule.onNodeWithText("Remove").assert(isNotEnabled())
    }

    @Test
    fun radioRow_rendersSubtitle_andClickInvokesOnClick() {
        var clicked = false
        setContent {
            RadioRow(title = "On demand", subtitle = "Manual only", selected = false, onClick = { clicked = true })
        }

        composeTestRule.onNodeWithText("Manual only").assertIsDisplayed()
        composeTestRule.onNodeWithText("On demand").performClick()
        assertTrue(clicked)
    }

    @Test
    fun radioRow_selectedWithoutSubtitle_renders() {
        setContent { RadioRow(title = "Always", selected = true, onClick = {}) }

        composeTestRule.onNodeWithText("Always").assertIsDisplayed()
    }

    @Test
    fun retentionSection_globalScreen_hidesUseDefault_andShowsBothGroups() {
        setContent {
            RetentionSection(count = 0, months = 0, includeUseDefault = false, onCountChange = {}, onMonthsChange = {})
        }

        composeTestRule.onNodeWithText(string(R.string.settings_retention)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.retention_count_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.retention_age_title)).assertIsDisplayed()
        // The global screen omits the "use the global default" option in both groups.
        composeTestRule.onAllNodesWithText(string(R.string.retention_use_default)).assertCountEquals(0)
    }

    @Test
    fun retentionSection_accountScreen_showsUseDefaultInBothGroups_andSelectingItReportsNull() {
        var reportedCount: Int? = -1
        setContent {
            RetentionSection(
                count = 500,
                months = 6,
                includeUseDefault = true,
                onCountChange = { reportedCount = it },
                onMonthsChange = {},
            )
        }

        // The per-account screen adds "use the global default" to the count group and the age group.
        composeTestRule.onAllNodesWithText(string(R.string.retention_use_default)).assertCountEquals(2)

        // The count group renders first, so its "use default" row is index 0; tapping it persists null.
        composeTestRule.onAllNodesWithText(string(R.string.retention_use_default))[0].performScrollTo().performClick()
        assertEquals(null, reportedCount)
    }

    @Test
    fun retentionSection_selectingACountOption_reportsItsValue() {
        var reportedCount: Int? = null
        setContent {
            RetentionSection(
                count = 0,
                months = 0,
                includeUseDefault = false,
                onCountChange = { reportedCount = it },
                onMonthsChange = {},
            )
        }

        composeTestRule.onNodeWithText(string(R.string.retention_count_500)).performScrollTo().performClick()
        assertEquals(500, reportedCount)
    }

    @Test
    fun retentionSection_selectingAnAgeOption_reportsItsMonths() {
        var reportedMonths: Int? = null
        setContent {
            RetentionSection(
                count = 0,
                months = 0,
                includeUseDefault = false,
                onCountChange = {},
                onMonthsChange = { reportedMonths = it },
            )
        }

        composeTestRule.onNodeWithText(string(R.string.retention_age_3m)).performScrollTo().performClick()
        assertEquals(3, reportedMonths)
    }
}
