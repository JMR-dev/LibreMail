// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.R
import org.libremail.reporting.ReportKind
import org.libremail.ui.navigation.Routes
import org.libremail.ui.reporting.ReportSummary
import org.libremail.ui.reporting.StartupReportViewModel
import org.libremail.ui.theme.LibreMailTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric JVM Compose test (#384, umbrella #373) for the JVM-tractable parts of `LibreMailApp.kt`:
 * the shared bottom bar ([LibreMailBottomBar]), the startup crash prompt ([StartupCrashPrompt] and its
 * dialog), and [LibreMailApp]'s cold-start "hold until known" guards. Runs on the JVM via the v2
 * `createComposeRule()` under [RobolectricTestRunner] — no emulator.
 *
 * Why the `LibreMailApp` glob stays in `jacocoNonJvmTestableSurface` (the acceptable exception noted in
 * #384): the [LibreMailApp] composable itself is a real `NavHost` whose non-onboarding start
 * destinations (mailbox, reader, settings, …) each call `hiltViewModel()`, and standing the graph up
 * needs `ViewModelStoreOwner` / `SavedStateRegistryOwner` / `OnBackPressedDispatcherOwner` a plain JVM
 * compose rule does not surface. Graph-level navigation therefore stays covered by the instrumented
 * `OnboardingFlowTest`; the render + interaction logic that CAN run headless is exercised here, and the
 * instrumented `LibreMailBottomBarTest` / `StartupCrashPromptTest` stay as the on-device E2E.
 *
 * The view models are mocked (their own logic has dedicated tests); this drives the composables' render
 * and callback branches only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class LibreMailAppJvmTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun string(resId: Int): String = context.getString(resId)

    /** RESUMED owner so the composables' `collectAsStateWithLifecycle` reads collect their state. */
    private val resumedOwner = object : LifecycleOwner {
        private val registry =
            LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private fun appViewModel(startDestination: String?, licenseAccepted: Boolean?): AppViewModel {
        val vm = mockk<AppViewModel>(relaxed = true)
        every { vm.startDestination } returns MutableStateFlow(startDestination)
        every { vm.licenseAccepted } returns MutableStateFlow(licenseAccepted)
        return vm
    }

    private fun startupViewModel(pending: ReportSummary? = null): StartupReportViewModel {
        val vm = mockk<StartupReportViewModel>(relaxed = true)
        every { vm.pendingCrash } returns MutableStateFlow(pending)
        return vm
    }

    private fun crash() = ReportSummary(id = CRASH_ID, kind = ReportKind.CRASH, createdAtMillis = 1L)

    private fun renderApp(appViewModel: AppViewModel, startupViewModel: StartupReportViewModel) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides resumedOwner) {
                LibreMailTheme(darkTheme = false, dynamicColor = false) {
                    LibreMailApp(appViewModel = appViewModel, startupViewModel = startupViewModel)
                }
            }
        }
    }

    private fun renderPrompt(viewModel: StartupReportViewModel, onReview: (String) -> Unit = {}) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides resumedOwner) {
                LibreMailTheme(darkTheme = false, dynamicColor = false) {
                    StartupCrashPrompt(viewModel = viewModel, onReview = onReview)
                }
            }
        }
    }

    // --- LibreMailApp cold-start "hold until known" guards (no NavHost is built on these paths) ---

    @Test
    fun nullStartDestination_holdsAndRendersNothing() {
        renderApp(
            appViewModel(startDestination = null, licenseAccepted = true),
            startupViewModel(),
        )

        // Nothing composes until BOTH the account-derived start and the license flag are known.
        composeTestRule.onNodeWithText(string(R.string.onboarding_welcome_title)).assertDoesNotExist()
    }

    @Test
    fun nullLicenseFlag_holdsAndRendersNothing() {
        renderApp(
            appViewModel(startDestination = Routes.ONBOARDING, licenseAccepted = null),
            startupViewModel(),
        )

        composeTestRule.onNodeWithText(string(R.string.onboarding_welcome_title)).assertDoesNotExist()
    }

    // --- StartupCrashPrompt (#255) + its dialog ---

    @Test
    fun startupCrashPrompt_noPendingCrash_showsNoDialog() {
        renderPrompt(startupViewModel(pending = null))

        composeTestRule.onNodeWithText(string(R.string.crash_prompt_title)).assertDoesNotExist()
    }

    @Test
    fun startupCrashPrompt_pendingCrash_showsDialog() {
        renderPrompt(startupViewModel(pending = crash()))

        composeTestRule.onNodeWithText(string(R.string.crash_prompt_title)).assertIsDisplayed()
    }

    @Test
    fun startupCrashPrompt_review_marksSurfacedAndReportsForReview() {
        val vm = startupViewModel(pending = crash())
        var reviewed: String? = null
        renderPrompt(vm, onReview = { reviewed = it })

        composeTestRule.onNodeWithText(string(R.string.crash_prompt_review)).performClick()

        verify { vm.dismiss(CRASH_ID) }
        assertEquals(CRASH_ID, reviewed)
    }

    @Test
    fun startupCrashPrompt_notNow_marksSurfacedWithoutReviewing() {
        val vm = startupViewModel(pending = crash())
        renderPrompt(vm)

        composeTestRule.onNodeWithText(string(R.string.crash_prompt_later)).performClick()

        verify { vm.dismiss(CRASH_ID) }
    }

    @Test
    fun startupCrashPrompt_discard_deletesTheReport() {
        val vm = startupViewModel(pending = crash())
        renderPrompt(vm)

        composeTestRule.onNodeWithText(string(R.string.crash_prompt_discard)).performClick()

        verify { vm.discard(CRASH_ID) }
    }

    // --- LibreMailBottomBar ---

    @Test
    fun bottomBar_rendersBothTabs() {
        composeTestRule.setContent {
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                LibreMailBottomBar(current = TopDest.MAILBOX, onSelect = {})
            }
        }

        composeTestRule.onNodeWithText(string(R.string.nav_mailbox)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.nav_settings)).assertIsDisplayed()
    }

    @Test
    fun bottomBar_tappingATab_reportsThatSelection() {
        var selected: TopDest? = null
        composeTestRule.setContent {
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                LibreMailBottomBar(current = TopDest.MAILBOX, onSelect = { selected = it })
            }
        }

        composeTestRule.onNodeWithText(string(R.string.nav_settings)).performClick()

        assertEquals(TopDest.SETTINGS, selected)
    }

    private companion object {
        const val CRASH_ID = "crash-1"
    }
}
