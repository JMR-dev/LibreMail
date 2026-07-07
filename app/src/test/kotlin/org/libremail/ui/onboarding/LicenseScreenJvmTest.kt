// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.onboarding

import android.content.Context
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
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
 * Robolectric JVM Compose test (#377, umbrella #373) for [LicenseScreen] (#172). Mirrors the
 * instrumented `LicenseScreenTest` on the JVM: Agree is gated on having scrolled to the end, Decline
 * always works, and the real bundled GPL-3.0 text (`res/raw/license.txt`) actually renders — proving
 * the merged Android resources loaded on the JVM. This file is dropped from
 * `jacocoNonJvmTestableSurface`; the instrumented test stays (and remains the coverage for the system
 * back press, which the JVM rule cannot drive — see [owner]).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class LicenseScreenJvmTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun string(resId: Int): String = context.getString(resId)

    // LicenseScreen's BackHandler requires both a lifecycle owner and an OnBackPressedDispatcherOwner
    // in the composition, so a resumed owner is provided for it to compose against. The actual
    // back-press → onDecline behaviour is covered by the instrumented LicenseScreenTest (kept per
    // #377); here the BackHandler registration is exercised by rendering and the onDecline path by the
    // Decline button, so this JVM test deliberately does not re-drive a system back press (which the
    // v2 Robolectric compose rule does not surface a reliable dispatch hook for).
    private val owner = object : OnBackPressedDispatcherOwner {
        private val registry =
            LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        private val dispatcher = OnBackPressedDispatcher()
        override val lifecycle: Lifecycle get() = registry
        override val onBackPressedDispatcher: OnBackPressedDispatcher get() = dispatcher
    }

    private fun setContent(onAgree: () -> Unit = {}, onDecline: () -> Unit = {}) {
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalLifecycleOwner provides owner,
                LocalOnBackPressedDispatcherOwner provides owner,
            ) {
                LibreMailTheme(darkTheme = false, dynamicColor = false) {
                    LicenseScreen(onAgree = onAgree, onDecline = onDecline)
                }
            }
        }
    }

    @Test
    fun licenseText_rendersTheRealBundledGplText() {
        setContent()

        // Proves res/raw/license.txt actually loaded, not just that some placeholder text exists.
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
}
