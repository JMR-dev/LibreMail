// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.ui.theme.LibreMailTheme

@RunWith(AndroidJUnit4::class)
class AppSmokeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun theme_rendersContent() {
        composeTestRule.setContent {
            LibreMailTheme {
                Text("LibreMail")
            }
        }
        composeTestRule.onNodeWithText("LibreMail").assertExists()
    }
}
