// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.onboarding

import android.content.Context
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
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
 * Robolectric JVM Compose test (#377, umbrella #373) for the first-run welcome. Renders
 * [WelcomeContent] directly for the render + "add account" interaction — as the instrumented
 * `OnboardingWelcomeScreenTest` does — to avoid the real POST_NOTIFICATIONS permission dialog that
 * [OnboardingWelcomeScreen]'s `NotificationPermissionEffect` would otherwise fire on a device.
 *
 * A separate case does drive the full [OnboardingWelcomeScreen] wrapper (Scaffold + the notification
 * effect), because on the JVM the effect's launcher can be wired to a no-op [ActivityResultRegistry]
 * so it never surfaces a system dialog — bringing the wrapper + effect into JVM coverage too. This
 * file is dropped from `jacocoNonJvmTestableSurface`; the instrumented test stays.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class OnboardingWelcomeScreenJvmTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun string(resId: Int): String = context.getString(resId)

    /**
     * A no-op [ActivityResultRegistry] so [OnboardingWelcomeScreen]'s notification-permission launcher
     * can register and fire on the JVM without ever surfacing a real system permission dialog.
     */
    private val noopRegistryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                // Intentionally never dispatch a result: the launch is a no-op in this JVM test.
            }
        }
    }

    @Test
    fun welcomeContent_rendersTitleSubtitleAndAddAccount() {
        composeTestRule.setContent {
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                WelcomeContent(onAddAccount = {})
            }
        }

        composeTestRule.onNodeWithText(string(R.string.onboarding_welcome_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.onboarding_welcome_subtitle)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.onboarding_add_account)).assertIsDisplayed()
    }

    @Test
    fun welcomeContent_tappingAddAccount_invokesCallback() {
        var added = false
        composeTestRule.setContent {
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                WelcomeContent(onAddAccount = { added = true })
            }
        }

        composeTestRule.onNodeWithText(string(R.string.onboarding_add_account)).performClick()

        assertTrue(added)
    }

    @Test
    fun onboardingWelcomeScreen_rendersWelcome_andAddAccountReachesCallback() {
        var added = false
        composeTestRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides noopRegistryOwner) {
                LibreMailTheme(darkTheme = false, dynamicColor = false) {
                    OnboardingWelcomeScreen(onAddAccount = { added = true })
                }
            }
        }

        // The full screen (Scaffold + NotificationPermissionEffect) composes and shows the welcome copy.
        composeTestRule.onNodeWithText(string(R.string.onboarding_welcome_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.onboarding_add_account)).performClick()

        assertTrue(added)
    }
}
