// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.onboarding

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
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
 * Robolectric JVM Compose test (#377, umbrella #373) for the onboarding battery opt-in step (#49).
 * The whole screen is the [OnboardingViewModel] wrapper, so it is driven with a mocked view model
 * exposing the `batteryUnrestricted` StateFlow: the "already unrestricted → done" state and the
 * "offered" state (guidance + Take me there + Not now) are both rendered, the Take-me-there path is
 * asserted to mark the prompt handled and resolve the settings intent, and the ON_RESUME re-read is
 * verified. Mirrors the instrumented `BatteryOptimizationStepTest` (which covers the same paths
 * through the real view model + NavHost). This file is dropped from `jacocoNonJvmTestableSurface`;
 * the instrumented test stays.
 */
// A tall display (`+h2000dp`, merged onto the default device config) so the centered, non-scrolling
// column fits its full "offered" state — guidance + Take me there + Not now — without the lower
// controls clipping below Robolectric's small default viewport (which would make performClick miss).
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "+w411dp-h2000dp")
class BatteryOptimizationScreenJvmTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun string(resId: Int): String = context.getString(resId)

    // Force the reduced-motion (static) illustration so the looping guide animation never spins the
    // Robolectric clock (which would hang waitForIdle); the animated path is covered by
    // BatteryGuideAnimationJvmTest. Also exercises the screen's default rememberReducedMotion argument.
    @Before
    fun forceReducedMotion() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    /** RESUMED owner so `collectAsStateWithLifecycle` collects and the ON_RESUME effect fires. */
    private val resumedOwner = object : LifecycleOwner {
        private val registry =
            LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private fun setContent(viewModel: OnboardingViewModel, onFinish: () -> Unit = {}) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides resumedOwner) {
                LibreMailTheme(darkTheme = false, dynamicColor = false) {
                    BatteryOptimizationScreen(viewModel = viewModel, onFinish = onFinish)
                }
            }
        }
    }

    @Test
    fun unrestricted_showsDoneState_andContinues() {
        val vm = mockk<OnboardingViewModel>(relaxed = true)
        every { vm.batteryUnrestricted } returns MutableStateFlow(true)
        var finished = false
        setContent(vm, onFinish = { finished = true })

        composeTestRule.onNodeWithText(string(R.string.onboarding_battery_done_title)).assertIsDisplayed()
        // The request/skip affordances — and the how-to guide illustration — are gone in the "done" state.
        composeTestRule.onNodeWithText(string(R.string.onboarding_battery_take_me)).assertDoesNotExist()
        composeTestRule.onNodeWithText(string(R.string.onboarding_battery_not_now)).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(string(R.string.onboarding_battery_animation_description))
            .assertDoesNotExist()

        composeTestRule.onNodeWithText(string(R.string.onboarding_battery_continue)).performClick()
        assertTrue(finished)
        // The ON_RESUME effect re-reads the live status.
        verify { vm.refreshBatteryStatus() }
    }

    @Test
    fun notUnrestricted_takeMeThere_marksHandledAndResolvesSettingsIntent() {
        val vm = mockk<OnboardingViewModel>(relaxed = true)
        every { vm.batteryUnrestricted } returns MutableStateFlow(false)
        every { vm.batterySettingsIntent() } returns Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        var finished = false
        setContent(vm, onFinish = { finished = true })

        composeTestRule.onNodeWithText(string(R.string.onboarding_battery_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.onboarding_battery_guidance)).assertIsDisplayed()
        // The illustrated "Battery → Unrestricted" guide is shown (as one TalkBack-friendly node).
        composeTestRule.onNodeWithContentDescription(string(R.string.onboarding_battery_animation_description))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.onboarding_battery_take_me)).performClick()

        // Take me there marks the prompt handled up front, then launches the resolved settings intent.
        verify { vm.markBatteryPromptHandled() }
        verify { vm.batterySettingsIntent() }
        // Leaving for Settings does not itself finish onboarding.
        assertFalse(finished)
    }

    @Test
    fun notUnrestricted_notNow_finishes() {
        val vm = mockk<OnboardingViewModel>(relaxed = true)
        every { vm.batteryUnrestricted } returns MutableStateFlow(false)
        var finished = false
        setContent(vm, onFinish = { finished = true })

        composeTestRule.onNodeWithText(string(R.string.onboarding_battery_not_now)).performClick()

        assertTrue(finished)
    }
}
