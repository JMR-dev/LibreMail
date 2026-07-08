// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.lock

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
 * UI tests for the app-lock gate. [LockScreen] is presentational (its biometric prompt is driven by
 * the caller via [onUnlock]), so it is exercised in isolation: the locked title/body always show, an
 * optional error string appears only when non-null, and the unlock button reports back.
 */
@RunWith(AndroidJUnit4::class)
class LockScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun string(resId: Int) = composeTestRule.activity.getString(resId)

    private fun setContent(error: String? = null, onUnlock: () -> Unit = {}) {
        composeTestRule.setContent {
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                LockScreen(error = error, onUnlock = onUnlock)
            }
        }
    }

    @Test
    fun showsLockedTitleBody_andUnlockButton() {
        setContent()

        composeTestRule.onNodeWithText(string(R.string.app_lock_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.app_lock_locked_body)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.app_lock_unlock)).assertIsDisplayed()
    }

    @Test
    fun noError_hidesErrorText() {
        setContent(error = null)

        composeTestRule.onNodeWithText(string(R.string.app_lock_unlock_failed)).assertDoesNotExist()
    }

    @Test
    fun error_isDisplayed() {
        setContent(error = string(R.string.app_lock_unlock_failed))

        composeTestRule.onNodeWithText(string(R.string.app_lock_unlock_failed)).assertIsDisplayed()
    }

    @Test
    fun tappingUnlock_invokesOnUnlock() {
        var unlocked = false
        setContent(onUnlock = { unlocked = true })

        composeTestRule.onNodeWithText(string(R.string.app_lock_unlock)).performClick()

        composeTestRule.waitUntil(5_000) { unlocked }
    }
}
