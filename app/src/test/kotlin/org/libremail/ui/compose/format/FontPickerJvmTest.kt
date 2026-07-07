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
 * Robolectric JVM port of the instrumented `FontPickerTest` (#376, umbrella #373): drives the
 * formatting toolbar's font-family dropdown on the JVM under [RobolectricTestRunner] via the v2
 * `createComposeRule()` — no emulator — so [FontPicker] counts toward JaCoCo's JVM-testable surface.
 * Exercises the anchor label for the null (Default) and selected states plus opening the menu and
 * picking a font / the Default entry, which drives the null-vs-CSS-stack `onSelect` branch. The
 * instrumented `FontPickerTest` stays as the on-device E2E. See
 * [org.libremail.ui.onboarding.AddAnotherAccountScreenJvmTest] for the pattern.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class FontPickerJvmTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun string(resId: Int): String = context.getString(resId)

    private fun setContent(selectedCss: String?, onSelect: (String?) -> Unit = {}) {
        composeTestRule.setContent {
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                FontPicker(selectedCss = selectedCss, onSelect = onSelect)
            }
        }
    }

    @Test
    fun noFontSelected_buttonShowsDefaultLabel() {
        setContent(selectedCss = null)

        composeTestRule.onNodeWithText(string(R.string.format_font_default)).assertIsDisplayed()
    }

    @Test
    fun aFontSelected_buttonShowsItsDisplayName() {
        val inter = FontRegistry.choices.first { it.name == "Inter" }
        setContent(selectedCss = inter.css)

        composeTestRule.onNodeWithText("Inter").assertIsDisplayed()
    }

    @Test
    fun tappingTheButton_opensAMenuListingEveryRegistryFont() {
        setContent(selectedCss = null)

        composeTestRule.onNodeWithText(string(R.string.format_font_default)).performClick()

        FontRegistry.choices.forEach { choice ->
            composeTestRule.onNodeWithText(choice.name).assertIsDisplayed()
        }
    }

    @Test
    fun pickingAFontFromTheMenu_reportsItsCssStack() {
        var picked: String? = "unset"
        val lora = FontRegistry.choices.first { it.name == "Lora" }
        setContent(selectedCss = null) { picked = it }

        composeTestRule.onNodeWithText(string(R.string.format_font_default)).performClick()
        composeTestRule.onNodeWithText("Lora").performClick()

        assertEquals(lora.css, picked)
    }

    @Test
    fun pickingDefaultFromTheMenu_clearsBySelectingNull() {
        var picked: String? = "unset"
        val inter = FontRegistry.choices.first { it.name == "Inter" }
        // The button reads "Inter" here, so the menu's own "Default" entry is the only such match.
        setContent(selectedCss = inter.css) { picked = it }

        composeTestRule.onNodeWithText("Inter").performClick()
        composeTestRule.onNodeWithText(string(R.string.format_font_default)).performClick()

        assertEquals(null, picked)
    }
}
