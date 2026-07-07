// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.lock

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
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
 * Robolectric JVM Compose test (#384, umbrella #373) for [AppLockGateHost], the gate that wraps the
 * whole app behind the screen lock. Mirrors on the JVM — via the v2 `createComposeRule()` under
 * [RobolectricTestRunner], no emulator — the three [AppLockUiState] branches the host renders:
 * [content] is composed only once unlocked, a blank cover hides it while [AppLockUiState.Checking], and
 * the [LockScreen] is drawn OVER the (still-composed) content while [AppLockUiState.Locked]. So
 * [AppLockGateHost] counts toward JaCoCo's JVM-testable surface (this file is dropped from
 * `jacocoNonJvmTestableSurface`).
 *
 * [AppLockViewModel] is mocked (its own gate/crypto logic is covered by `AppLockViewModelTest`); this
 * exercises the host composable's render + latch branches. The `BiometricPrompt` itself is device-only
 * (see `showAppLockPrompt`): with no hosting `FragmentActivity` in this JVM rule, requesting auth
 * resolves to `onAuthError(null)` instead of presenting a prompt — which is the branch asserted here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class AppLockGateHostJvmTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun string(resId: Int): String = context.getString(resId)

    /** RESUMED owner so `collectAsStateWithLifecycle` collects and the ON_START effect fires. */
    private val resumedOwner = object : LifecycleOwner {
        private val registry =
            LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private fun viewModel(state: AppLockUiState): AppLockViewModel {
        val vm = mockk<AppLockViewModel>(relaxed = true)
        every { vm.uiState } returns MutableStateFlow(state)
        return vm
    }

    private fun setContent(viewModel: AppLockViewModel) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides resumedOwner) {
                LibreMailTheme(darkTheme = false, dynamicColor = false) {
                    AppLockGateHost(viewModel = viewModel) { Text(CONTENT) }
                }
            }
        }
    }

    @Test
    fun unlocked_showsAppContent() {
        setContent(viewModel(AppLockUiState.Unlocked))

        composeTestRule.onNodeWithText(CONTENT).assertIsDisplayed()
    }

    @Test
    fun checking_coversContent_showingNeitherAppContentNorLockScreen() {
        setContent(viewModel(AppLockUiState.Checking))

        // The Checking cover is blank: content is not composed yet (never unlocked), and no lock UI shows.
        composeTestRule.onNodeWithText(CONTENT).assertDoesNotExist()
        composeTestRule.onNodeWithText(string(R.string.app_lock_title)).assertDoesNotExist()
    }

    @Test
    fun locked_showsLockScreen_andHidesContentBeforeTheFirstUnlock() {
        setContent(viewModel(AppLockUiState.Locked()))

        composeTestRule.onNodeWithText(string(R.string.app_lock_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(CONTENT).assertDoesNotExist()
    }

    @Test
    fun locked_autoRequestsAuth_reportingAuthErrorWhenNoFragmentActivityHostsIt() {
        val vm = viewModel(AppLockUiState.Locked())
        setContent(vm)

        // Entering the Locked branch auto-fires authenticate(); with no hosting FragmentActivity in this
        // JVM rule it resolves to onAuthError(null) rather than presenting a device-only BiometricPrompt.
        verify(timeout = 2_000) { vm.onAuthError(null) }
    }

    @Test
    fun reLockAfterUnlock_drawsLockScreenButKeepsContentComposed() {
        val state = MutableStateFlow<AppLockUiState>(AppLockUiState.Unlocked)
        val vm = mockk<AppLockViewModel>(relaxed = true)
        every { vm.uiState } returns state
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides resumedOwner) {
                LibreMailTheme(darkTheme = false, dynamicColor = false) {
                    AppLockGateHost(viewModel = vm) { Text(CONTENT) }
                }
            }
        }
        composeTestRule.onNodeWithText(CONTENT).assertIsDisplayed()

        // Re-lock: once ever unlocked, the content stays composed (its nav/scroll/draft state survives)
        // and the lock screen is drawn OVER it rather than replacing it.
        state.value = AppLockUiState.Locked()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(string(R.string.app_lock_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(CONTENT).assertExists()
    }

    private companion object {
        const val CONTENT = "app-content-marker"
    }
}
