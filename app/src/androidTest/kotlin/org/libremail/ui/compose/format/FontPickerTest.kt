// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.compose.format

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.R
import org.libremail.ui.theme.LibreMailTheme

/**
 * UI tests for the compose formatting toolbar's font-family picker (#72). [FontPicker] is
 * presentational, so it is driven in isolation - independent of the surrounding
 * [org.libremail.ui.compose.RichTextBodyField] editor - mirroring how `FontSizePickerTest`
 * exercises its dropdown.
 */
@RunWith(AndroidJUnit4::class)
class FontPickerTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun string(resId: Int) = composeTestRule.activity.getString(resId)

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
