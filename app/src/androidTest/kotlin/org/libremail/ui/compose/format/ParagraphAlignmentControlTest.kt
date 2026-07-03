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
import org.libremail.richtext.RichAlign
import org.libremail.ui.theme.LibreMailTheme

/**
 * UI tests for the compose formatting toolbar's three-state paragraph-alignment control (#76).
 * [ParagraphAlignmentControl] is presentational, so it is driven in isolation - independent of the
 * surrounding [org.libremail.ui.compose.RichTextBodyField] editor - mirroring how `FontSizePickerTest`
 * exercises its picker.
 */
@RunWith(AndroidJUnit4::class)
class ParagraphAlignmentControlTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun setContent(selected: RichAlign?, onSelect: (RichAlign) -> Unit = {}) {
        composeTestRule.setContent {
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                ParagraphAlignmentControl(selected = selected, onSelect = onSelect)
            }
        }
    }

    @Test
    fun showsAllThreeAlignmentGlyphs() {
        setContent(selected = RichAlign.START)
        composeTestRule.onNodeWithText(ALIGN_START_GLYPH).assertIsDisplayed()
        composeTestRule.onNodeWithText(ALIGN_CENTER_GLYPH).assertIsDisplayed()
        composeTestRule.onNodeWithText(ALIGN_END_GLYPH).assertIsDisplayed()
    }

    @Test
    fun tappingCenter_reportsCenter() {
        var picked: RichAlign? = null
        setContent(selected = RichAlign.START) { picked = it }
        composeTestRule.onNodeWithText(ALIGN_CENTER_GLYPH).performClick()
        assertEquals(RichAlign.CENTER, picked)
    }

    @Test
    fun tappingEnd_reportsEnd() {
        var picked: RichAlign? = null
        setContent(selected = RichAlign.START) { picked = it }
        composeTestRule.onNodeWithText(ALIGN_END_GLYPH).performClick()
        assertEquals(RichAlign.END, picked)
    }

    @Test
    fun tappingStart_reportsStart() {
        var picked: RichAlign? = null
        setContent(selected = RichAlign.CENTER) { picked = it }
        composeTestRule.onNodeWithText(ALIGN_START_GLYPH).performClick()
        assertEquals(RichAlign.START, picked)
    }
}
