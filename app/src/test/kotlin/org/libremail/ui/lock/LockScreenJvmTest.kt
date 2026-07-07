// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.lock

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
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
 * Robolectric JVM Compose test (#377, umbrella #373) for the app-lock gate. Mirrors the instrumented
 * [LockScreenTest] on the JVM via the v2 `createComposeRule()` under [RobolectricTestRunner], so
 * [LockScreen]'s render + interaction paths become JaCoCo JVM-testable (this file is dropped from
 * `jacocoNonJvmTestableSurface`). The instrumented `androidTest` [LockScreenTest] stays.
 *
 * [LockScreen] is presentational — its biometric prompt is driven by the caller via [onUnlock] — so
 * it is exercised in isolation: the locked title/body always show, an optional error string appears
 * only when non-null, and the unlock button reports back.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class LockScreenJvmTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun string(resId: Int): String = context.getString(resId)

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

        assertTrue(unlocked)
    }
}
