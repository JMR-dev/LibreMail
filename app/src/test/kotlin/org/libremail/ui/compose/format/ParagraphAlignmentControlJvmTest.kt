// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.compose.format

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.richtext.RichAlign
import org.libremail.ui.theme.LibreMailTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric JVM port of the instrumented `ParagraphAlignmentControlTest` (#376, umbrella #373):
 * drives the three-state paragraph-alignment control on the JVM under [RobolectricTestRunner] via
 * the v2 `createComposeRule()` — no emulator — so [ParagraphAlignmentControl] counts toward JaCoCo's
 * JVM-testable surface. Each button is a bare glyph, so nodes are addressed by the shared glyph
 * constants. The instrumented `ParagraphAlignmentControlTest` stays as the on-device E2E. See
 * [org.libremail.ui.onboarding.AddAnotherAccountScreenJvmTest] for the pattern.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ParagraphAlignmentControlJvmTest {

    @get:Rule
    val composeTestRule = createComposeRule()

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
