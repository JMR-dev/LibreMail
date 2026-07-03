// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.onboarding

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.R
import org.libremail.ui.theme.LibreMailTheme

/**
 * Component test for [LicenseScreen] (#172), in isolation from the real onboarding nav graph (that
 * wiring — persisting acceptance, popping the license off the back stack, calling `finish()` on
 * Decline — belongs to `onboardingGraph()` in `LibreMailApp.kt`, not this composable). This test only
 * owns the screen's own contract: Agree is gated on having scrolled to the end, Decline (and back)
 * always works, and the real bundled GPL-3.0 text actually renders.
 */
@RunWith(AndroidJUnit4::class)
class LicenseScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun string(resId: Int) = composeTestRule.activity.getString(resId)

    private fun setContent(onAgree: () -> Unit = {}, onDecline: () -> Unit = {}) {
        composeTestRule.setContent {
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                LicenseScreen(onAgree = onAgree, onDecline = onDecline)
            }
        }
    }

    @Test
    fun licenseText_rendersTheRealBundledGplText() {
        setContent()

        // Proves res/raw/license.txt actually loaded, not just that some placeholder text exists —
        // an empty or missing resource would make agreeButton_isDisabledUntilScrolledToTheEnd below
        // pass for the wrong reason (nothing to scroll).
        composeTestRule.onNodeWithText("GNU GENERAL PUBLIC LICENSE", substring = true).assertExists()
    }

    @Test
    fun agreeButton_isDisabledUntilScrolledToTheEnd() {
        setContent()

        composeTestRule.onNodeWithText(string(R.string.license_agree)).assertIsNotEnabled()
    }

    @Test
    fun agreeButton_scrolledToEnd_becomesEnabledAndInvokesOnAgree() {
        var agreed = false
        setContent(onAgree = { agreed = true })

        composeTestRule.onNodeWithTag(LICENSE_SCROLL_END_TAG).performScrollTo()

        composeTestRule.onNodeWithText(string(R.string.license_agree)).assertIsEnabled().performClick()
        assertTrue(agreed)
    }

    @Test
    fun declineButton_worksWithoutScrolling_andInvokesOnDecline() {
        var declined = false
        setContent(onDecline = { declined = true })

        // No scrolling first: Decline must never be gated the way Agree is.
        composeTestRule.onNodeWithText(string(R.string.license_decline)).assertIsEnabled().performClick()

        assertTrue(declined)
    }

    @Test
    fun systemBack_invokesOnDeclineJustLikeTheButton() {
        var declined = false
        setContent(onDecline = { declined = true })

        Espresso.pressBack()

        assertTrue(declined)
    }
}
