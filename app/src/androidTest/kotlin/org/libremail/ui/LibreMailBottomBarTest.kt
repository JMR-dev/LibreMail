// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.R
import org.libremail.ui.theme.LibreMailTheme

/** UI test for the shared bottom navigation bar: it renders both tabs and reports the tapped tab. */
@RunWith(AndroidJUnit4::class)
class LibreMailBottomBarTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun string(resId: Int) = composeTestRule.activity.getString(resId)

    @Test
    fun rendersBothTabs_andEmitsSelectionOnTap() {
        var selected: TopDest? = null
        composeTestRule.setContent {
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                LibreMailBottomBar(current = TopDest.MAILBOX, onSelect = { selected = it })
            }
        }

        composeTestRule.onNodeWithText(string(R.string.nav_mailbox)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.nav_settings)).assertIsDisplayed()

        composeTestRule.onNodeWithText(string(R.string.nav_settings)).performClick()
        assertEquals(TopDest.SETTINGS, selected)
    }
}
