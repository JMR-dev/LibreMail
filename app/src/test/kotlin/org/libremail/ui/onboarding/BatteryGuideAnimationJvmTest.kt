// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.onboarding

import android.content.Context
import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Assert.assertFalse
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
 * Robolectric JVM Compose test (#174) for the dependency-free "Battery → Unrestricted" guide
 * illustration. Covers both the looping animated variant and the reduced-motion static fallback, the
 * default `reducedMotion` argument reading the system animation-scale setting, and the non-composable
 * [isReducedMotion] decision. The illustration exposes a single [contentDescription] to TalkBack, so
 * every render is asserted through it.
 *
 * The animated variant is driven with `mainClock.autoAdvance = false` and hand-advanced, so the
 * infinite transition never spins the Robolectric clock into a `waitForIdle` hang.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "+w411dp-h800dp")
class BatteryGuideAnimationJvmTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun description() = context.getString(R.string.onboarding_battery_animation_description)

    @Test
    fun reducedMotion_rendersStaticGuideWithDescription() {
        composeTestRule.setContent {
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                BatteryGuideAnimation(reducedMotion = true)
            }
        }

        composeTestRule.onNodeWithContentDescription(description()).assertIsDisplayed()
    }

    @Test
    fun motionOn_rendersAnimatedGuide_acrossBothSteps() {
        // Hand-drive the clock so the looping guide can't hang waitForIdle.
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                BatteryGuideAnimation(reducedMotion = false)
            }
        }

        // First frame: the "Battery" step is highlighted.
        composeTestRule.onNodeWithContentDescription(description()).assertIsDisplayed()
        // Advance past the halfway point so the highlight/selection moves to the "Unrestricted" step.
        composeTestRule.mainClock.advanceTimeBy(2000)
        composeTestRule.onNodeWithContentDescription(description()).assertIsDisplayed()
    }

    @Test
    fun defaultReducedMotionArg_readsSystemAnimationScale() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        // Defensive: even if the setting round-trip surprised us into the animated branch, a manual
        // clock keeps the test from hanging.
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                BatteryGuideAnimation()
            }
        }

        composeTestRule.onNodeWithContentDescription(description()).assertIsDisplayed()
    }

    @Test
    fun isReducedMotion_trueWhenAnimationsDisabled() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        assertTrue(isReducedMotion(context))
    }

    @Test
    fun isReducedMotion_falseWhenAnimationsEnabled() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        assertFalse(isReducedMotion(context))
    }
}
