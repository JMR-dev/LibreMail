// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.compose.format

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
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
 * Robolectric JVM port of the instrumented `FontSizePickerTest` (#376, umbrella #373): drives the
 * formatting toolbar's font-size dropdown on the JVM under [RobolectricTestRunner] via the v2
 * `createComposeRule()` — no emulator — so [FontSizePicker] counts toward JaCoCo's JVM-testable
 * surface. Exercises the anchor label for the null (Default) and selected states plus opening the
 * menu and picking a preset / the Default entry, which drives the null-vs-point-size `onSelect`
 * branch. The instrumented `FontSizePickerTest` stays as the on-device E2E. See
 * [org.libremail.ui.onboarding.AddAnotherAccountScreenJvmTest] for the pattern.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class FontSizePickerJvmTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun string(resId: Int): String = context.getString(resId)

    private fun string(resId: Int, vararg args: Any): String = context.getString(resId, *args)

    private fun setContent(selectedPt: Int?, onSelect: (Int?) -> Unit = {}) {
        composeTestRule.setContent {
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                FontSizePicker(selectedPt = selectedPt, onSelect = onSelect)
            }
        }
    }

    @Test
    fun noSizeSelected_buttonShowsDefaultLabel() {
        setContent(selectedPt = null)

        composeTestRule.onNodeWithText(string(R.string.format_size_default)).assertIsDisplayed()
    }

    @Test
    fun aSizeSelected_buttonShowsItsPointValue() {
        setContent(selectedPt = 18)

        composeTestRule.onNodeWithText(string(R.string.format_size_pt, 18)).assertIsDisplayed()
    }

    @Test
    fun tappingTheButton_opensAMenuListingDefaultAndEveryPreset() {
        setContent(selectedPt = null)

        // Before the menu opens, "Default" only labels the anchor button itself - a unique match.
        composeTestRule.onNodeWithText(string(R.string.format_size_default)).performClick()

        FONT_SIZE_PRESETS_PT.forEach { pt ->
            composeTestRule.onNodeWithText(string(R.string.format_size_pt, pt)).assertIsDisplayed()
        }
    }

    @Test
    fun pickingAPresetFromTheMenu_reportsItsPointSize() {
        var picked: Int? = -1
        setContent(selectedPt = null) { picked = it }

        composeTestRule.onNodeWithText(string(R.string.format_size_default)).performClick()
        composeTestRule.onNodeWithText(string(R.string.format_size_pt, 14)).performClick()

        assertEquals(14, picked)
    }

    @Test
    fun pickingDefaultFromTheMenu_clearsBySelectingNull() {
        var picked: Int? = 12
        setContent(selectedPt = 12) { picked = it }

        // The button reads "12 pt" here, so the menu's own "Default" entry is the only such match.
        composeTestRule.onNodeWithText(string(R.string.format_size_pt, 12)).performClick()
        composeTestRule.onNodeWithText(string(R.string.format_size_default)).performClick()

        assertEquals(null, picked)
    }
}
