// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.compose.format

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
 * Robolectric JVM port of the instrumented `ColorSwatchRowTest` (#376, umbrella #373): drives the
 * shared font-color / highlight swatch row on the JVM under [RobolectricTestRunner] via the v2
 * `createComposeRule()` — no emulator — so [ColorSwatchRow] and its [ColorSwatch] model count toward
 * JaCoCo's JVM-testable surface. Every swatch is a TalkBack-labeled, selectable button, so nodes are
 * addressed by their content description. The instrumented `ColorSwatchRowTest` stays as the
 * on-device E2E. See [org.libremail.ui.onboarding.AddAnotherAccountScreenJvmTest] for the pattern.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ColorSwatchRowJvmTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun string(resId: Int): String = context.getString(resId)

    private val red = ColorSwatch(argb = 0xFFD32F2F.toInt(), label = "Red")
    private val blue = ColorSwatch(argb = 0xFF1976D2.toInt(), label = "Blue")

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
