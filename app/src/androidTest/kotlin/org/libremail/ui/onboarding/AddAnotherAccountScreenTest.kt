// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.onboarding

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.R
import org.libremail.ui.theme.LibreMailTheme

/**
 * UI tests for the post-add onboarding prompt. [AddAnotherAccountScreen] is presentational — it just
 * confirms the add and routes the two choices back to the caller — so it is driven in isolation:
 * "Add another" returns to the vendor picker and "No" finishes onboarding.
 */
@RunWith(AndroidJUnit4::class)
class AddAnotherAccountScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun string(resId: Int) = composeTestRule.activity.getString(resId)

    private fun setContent(onAddAnother: () -> Unit = {}, onFinish: () -> Unit = {}) {
        composeTestRule.setContent {
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                AddAnotherAccountScreen(onAddAnother = onAddAnother, onFinish = onFinish)
            }
        }
    }

    @Test
    fun showsConfirmation_andBothChoices() {
        setContent()

        composeTestRule.onNodeWithText(string(R.string.onboarding_account_added_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.onboarding_add_another_prompt)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.onboarding_add_another_yes)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.onboarding_add_another_no)).assertIsDisplayed()
    }

    @Test
    fun tappingAddAnother_invokesOnAddAnother() {
        var addAnother = false
        setContent(onAddAnother = { addAnother = true })

        composeTestRule.onNodeWithText(string(R.string.onboarding_add_another_yes)).performClick()

        composeTestRule.waitUntil(5_000) { addAnother }
    }

    @Test
    fun tappingNo_invokesOnFinish() {
        var finished = false
        setContent(onFinish = { finished = true })

        composeTestRule.onNodeWithText(string(R.string.onboarding_add_another_no)).performClick()

        composeTestRule.waitUntil(5_000) { finished }
    }
}
