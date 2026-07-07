// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.security

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.R
import org.libremail.ui.theme.LibreMailTheme

/**
 * UI coverage for the fail-closed encrypted-cache error screen (issue #359). [CacheEncryptionErrorScreen]
 * is presentational (its report action is wired by the caller), so it is exercised in isolation: the
 * verbatim error message shows, and tapping "Report a problem" reports back.
 */
@RunWith(AndroidJUnit4::class)
class CacheEncryptionErrorScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun string(resId: Int) = composeTestRule.activity.getString(resId)

    private fun setContent(onReportProblem: () -> Unit = {}) {
        composeTestRule.setContent {
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                CacheEncryptionErrorScreen(onReportProblem = onReportProblem)
            }
        }
    }

    @Test
    fun showsTheVerbatimErrorMessageAndReportAction() {
        setContent()

        // The exact maintainer-specified message must render, unchanged.
        composeTestRule.onNodeWithText(string(R.string.cache_encryption_error_message)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.cache_encryption_report_action)).assertIsDisplayed()
    }

    @Test
    fun tappingReportProblem_invokesCallback() {
        var reported = false
        setContent(onReportProblem = { reported = true })

        composeTestRule.onNodeWithText(string(R.string.cache_encryption_report_action)).performClick()

        composeTestRule.waitUntil(5_000) { reported }
    }
}
