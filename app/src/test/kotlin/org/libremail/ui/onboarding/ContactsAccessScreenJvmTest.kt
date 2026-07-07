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
 * Robolectric JVM Compose test (#377, umbrella #373) for the onboarding contacts-access step (#127,
 * #128). Two layers:
 *  - the stateless [ContactsAccessContent] driven with explicit signals for its three paths — skip,
 *    grant (the "done" state), and request (with the re-ask rationale) — mirroring the instrumented
 *    `ContactsAccessStepTest`;
 *  - the [ContactsAccessScreen] wrapper driven with a mocked [OnboardingViewModel] exposing the
 *    `contactsGranted` StateFlow, so the wrapper's own wiring (the allow → mark-requested → launch
 *    path, skip/continue, and the ON_RESUME re-read) gains JVM coverage without a live permission
 *    dialog.
 *
 * This file is dropped from `jacocoNonJvmTestableSurface`; the instrumented test stays.
 */
// A tall display (`+h2000dp`, merged onto the default device config) so the centered, non-scrolling
// column fits its full state — including the re-ask rationale plus Allow and Not now — without the
// lower controls clipping below Robolectric's small default viewport (which would make performClick
// miss).
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "+w411dp-h2000dp")
class ContactsAccessScreenJvmTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun string(resId: Int): String = context.getString(resId)

    /** RESUMED owner so `collectAsStateWithLifecycle` collects and the ON_RESUME effect fires. */
    private val resumedOwner = object : LifecycleOwner {
        private val registry =
            LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    /** A no-op registry so the wrapper's READ_CONTACTS launcher never surfaces a system dialog. */
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

    // --- Stateless ContactsAccessContent -------------------------------------------------------

    private fun setContent(
        granted: Boolean,
        showRationale: Boolean = false,
        onAllow: () -> Unit = {},
        onSkip: () -> Unit = {},
        onContinue: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                ContactsAccessContent(
                    granted = granted,
                    showRationale = showRationale,
                    onAllow = onAllow,
                    onSkip = onSkip,
                    onContinue = onContinue,
                )
            }
        }
    }

    @Test
    fun notGranted_notNow_skipsTheStep() {
        var skipped = false
        var allowed = false
        setContent(granted = false, onAllow = { allowed = true }, onSkip = { skipped = true })

        composeTestRule.onNodeWithText(string(R.string.onboarding_contacts_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.onboarding_contacts_not_now)).performClick()

        assertTrue("Not now must invoke the skip callback", skipped)
        assertFalse("Skipping must not request the permission", allowed)
    }

    @Test
    fun notGranted_allow_triggersTheRequest() {
        var allowed = false
        setContent(granted = false, onAllow = { allowed = true })

        composeTestRule.onNodeWithText(string(R.string.onboarding_contacts_allow)).performClick()

        assertTrue("Allow must trigger the permission request", allowed)
    }

    @Test
    fun granted_showsDoneState_andContinues() {
        var continued = false
        setContent(granted = true, onContinue = { continued = true })

        composeTestRule.onNodeWithText(string(R.string.onboarding_contacts_done_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.onboarding_contacts_allow)).assertDoesNotExist()
        composeTestRule.onNodeWithText(string(R.string.onboarding_contacts_not_now)).assertDoesNotExist()

        composeTestRule.onNodeWithText(string(R.string.onboarding_contacts_continue)).performClick()
        assertTrue("Continue must invoke the continue callback", continued)
    }

    @Test
    fun reRequest_showsRationale() {
        setContent(granted = false, showRationale = true)

        composeTestRule.onNodeWithText(string(R.string.onboarding_contacts_rationale)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.onboarding_contacts_allow)).assertIsDisplayed()
    }

    // --- ContactsAccessScreen wrapper (VM-driven) ----------------------------------------------

    private fun setWrapper(viewModel: OnboardingViewModel, onFinish: () -> Unit = {}) {
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalLifecycleOwner provides resumedOwner,
                LocalActivityResultRegistryOwner provides noopRegistryOwner,
            ) {
                LibreMailTheme(darkTheme = false, dynamicColor = false) {
                    ContactsAccessScreen(viewModel = viewModel, onFinish = onFinish)
                }
            }
        }
    }

    @Test
    fun wrapper_granted_showsDone_continuesAndRereadsOnResume() {
        val vm = mockk<OnboardingViewModel>(relaxed = true)
        every { vm.contactsGranted } returns MutableStateFlow(true)
        var finished = false
        setWrapper(vm, onFinish = { finished = true })

        composeTestRule.onNodeWithText(string(R.string.onboarding_contacts_done_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.onboarding_contacts_continue)).performClick()

        assertTrue(finished)
        // The ON_RESUME effect re-reads the live grant when the step comes to the fore.
        verify { vm.refreshContactsStatus() }
    }

    @Test
    fun wrapper_notGranted_allow_marksRequestedAndLaunches() {
        val vm = mockk<OnboardingViewModel>(relaxed = true)
        every { vm.contactsGranted } returns MutableStateFlow(false)
        setWrapper(vm)

        composeTestRule.onNodeWithText(string(R.string.onboarding_contacts_allow)).performClick()

        // Allow persists "the dialog was shown" up front, then launches the system request.
        verify { vm.markContactsPermissionRequested() }
    }

    @Test
    fun wrapper_notGranted_notNow_finishes() {
        val vm = mockk<OnboardingViewModel>(relaxed = true)
        every { vm.contactsGranted } returns MutableStateFlow(false)
        var finished = false
        setWrapper(vm, onFinish = { finished = true })

        composeTestRule.onNodeWithText(string(R.string.onboarding_contacts_not_now)).performClick()

        assertTrue(finished)
    }
}
