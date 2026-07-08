// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.compose.format

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.R
import org.libremail.ui.theme.LibreMailTheme

/**
 * UI tests for the shared font-color / highlight swatch row (#78). [ColorSwatchRow] is
 * presentational, so it is driven in isolation - independent of the compose formatting toolbar that
 * hosts it - mirroring how `ParagraphAlignmentControlTest` exercises its control. Every swatch is a
 * TalkBack-labeled, selectable button, so nodes are addressed by their content description.
 */
@RunWith(AndroidJUnit4::class)
class ColorSwatchRowTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val red = ColorSwatch(argb = 0xFFD32F2F.toInt(), label = "Red")
    private val blue = ColorSwatch(argb = 0xFF1976D2.toInt(), label = "Blue")

    private fun string(resId: Int) = composeTestRule.activity.getString(resId)

    private fun setContent(selectedArgb: Int?, onSelect: (Int?) -> Unit = {}) {
        composeTestRule.setContent {
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                ColorSwatchRow(swatches = listOf(red, blue), selectedArgb = selectedArgb, onSelect = onSelect)
            }
        }
    }

    @Test
    fun showsNoneEntry_andEverySwatch() {
        setContent(selectedArgb = null)

        composeTestRule.onNodeWithContentDescription(string(R.string.format_color_none)).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(red.label).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(blue.label).assertIsDisplayed()
    }

    @Test
    fun tappingASwatch_reportsItsArgb() {
        var picked: Int? = -1
        setContent(selectedArgb = null) { picked = it }

        composeTestRule.onNodeWithContentDescription(blue.label).performClick()

        assertEquals(blue.argb, picked)
    }

    @Test
    fun tappingNoneEntry_reportsNull() {
        var picked: Int? = red.argb
        setContent(selectedArgb = red.argb) { picked = it }

        composeTestRule.onNodeWithContentDescription(string(R.string.format_color_none)).performClick()

        assertEquals(null, picked)
    }

    @Test
    fun selectedSwatch_isMarkedSelected_andOthersAreNot() {
        setContent(selectedArgb = red.argb)

        composeTestRule.onNodeWithContentDescription(red.label).assertIsSelected()
        composeTestRule.onNodeWithContentDescription(blue.label).assertIsNotSelected()
        // With a color selected, the leading "no color" entry is not the selected one.
        composeTestRule.onNodeWithContentDescription(string(R.string.format_color_none)).assertIsNotSelected()
    }

    @Test
    fun noSelection_marksTheNoneEntrySelected() {
        setContent(selectedArgb = null)

        composeTestRule.onNodeWithContentDescription(string(R.string.format_color_none)).assertIsSelected()
        composeTestRule.onNodeWithContentDescription(red.label).assertIsNotSelected()
    }
}
