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
 * UI tests for the compose formatting toolbar's font-size dropdown (#73). [FontSizePicker] is
 * presentational, so it is driven directly - independent of the surrounding
 * [org.libremail.ui.compose.RichTextBodyField] editor - mirroring how `ContactAutocompleteRowTest`
 * exercises its row composable in isolation.
 */
@RunWith(AndroidJUnit4::class)
class FontSizePickerTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun string(resId: Int) = composeTestRule.activity.getString(resId)

    private fun string(resId: Int, vararg args: Any) = composeTestRule.activity.getString(resId, *args)

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
