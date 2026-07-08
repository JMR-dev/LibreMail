// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.accountsetup

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.R
import org.libremail.domain.model.MailProvider
import org.libremail.ui.theme.LibreMailTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric JVM Compose test (#378, umbrella #373) for the account-vendor picker. Mirrors the
 * instrumented [AccountPickerScreenTest] on the JVM via the v2 `createComposeRule()` under
 * [RobolectricTestRunner] — no emulator — so [AccountPickerScreen] counts toward JaCoCo's
 * JVM-testable surface (this file is dropped from `jacocoNonJvmTestableSurface`). The instrumented
 * `AccountPickerScreenTest` stays as the on-device E2E, and it (with Espresso-Intents) remains the
 * coverage for the actual Outlook browser launch the JVM rule cannot safely surface — here the
 * Outlook tap is driven through a mocked [AccountSetupViewModel] so no real activity ever starts.
 *
 * [AccountSetupViewModel] is mocked (its own logic is covered by [AccountSetupViewModelTest]); this
 * exercises the screen's render, routing, and the busy/error/done state branches.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// A tall display so the whole scrolling provider list fits Robolectric's small default viewport,
// keeping every row on-screen for assertIsDisplayed / performClick without scrolling.
@Config(sdk = [36], qualifiers = "+w411dp-h2000dp")
class AccountPickerScreenJvmTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun string(resId: Int): String = context.getString(resId)

    /** RESUMED owner so `collectAsStateWithLifecycle` collects the view-model state. */
    private val resumedOwner = object : LifecycleOwner {
        private val registry =
            LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    /**
     * A no-op registry so [AccountPickerScreen]'s `rememberLauncherForActivityResult` (the Outlook
     * sign-in launcher) can register and launch on the JVM without surfacing a real activity/browser.
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

    private fun viewModel(state: AccountSetupUiState = AccountSetupUiState()): AccountSetupViewModel {
        val vm = mockk<AccountSetupViewModel>(relaxed = true)
        every { vm.state } returns MutableStateFlow(state)
        return vm
    }

    private fun setContent(
        viewModel: AccountSetupViewModel,
        onBack: () -> Unit = {},
        onAccountAdded: (String) -> Unit = {},
        onPickProvider: (MailProvider) -> Unit = {},
        onManualSetup: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalLifecycleOwner provides resumedOwner,
                LocalActivityResultRegistryOwner provides noopRegistryOwner,
            ) {
                LibreMailTheme(darkTheme = false, dynamicColor = false) {
                    AccountPickerScreen(
                        onBack = onBack,
                        onAccountAdded = onAccountAdded,
                        onPickProvider = onPickProvider,
                        onManualSetup = onManualSetup,
                        viewModel = viewModel,
                    )
                }
            }
        }
    }

    @Test
    fun listsOutlook_everyAppPasswordVendor_andOther() {
        setContent(viewModel())

        composeTestRule.onNodeWithText(string(R.string.account_setup_subtitle)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.account_setup_outlook)).assertIsDisplayed()
        MailProvider.entries.forEach { provider ->
            composeTestRule.onNodeWithText(provider.displayName).assertIsDisplayed()
        }
        composeTestRule.onNodeWithText(string(R.string.account_setup_other)).assertIsDisplayed()
    }

    @Test
    fun tappingAppPasswordProvider_invokesOnPickProvider() {
        var picked: MailProvider? = null
        setContent(viewModel(), onPickProvider = { picked = it })

        composeTestRule.onNodeWithText(MailProvider.GMAIL.displayName).performClick()

        assertEquals(MailProvider.GMAIL, picked)
    }

    @Test
    fun tappingOther_invokesOnManualSetup() {
        var manualRequested = false
        setContent(viewModel(), onManualSetup = { manualRequested = true })

        composeTestRule.onNodeWithText(string(R.string.account_setup_other)).performClick()

        assertTrue(manualRequested)
    }

    @Test
    fun tappingBack_invokesOnBack() {
        var backed = false
        setContent(viewModel(), onBack = { backed = true })

        composeTestRule.onNodeWithContentDescription(string(R.string.action_back)).performClick()

        assertTrue(backed)
    }

    @Test
    fun tappingOutlook_buildsTheAuthIntentAndLaunchesIt() {
        val vm = viewModel()
        // A real (empty) Intent is safe here: the no-op registry never actually starts it.
        every { vm.outlookAuthIntent() } returns Result.success(Intent())
        setContent(vm)

        composeTestRule.onNodeWithText(string(R.string.account_setup_outlook)).performClick()

        verify { vm.outlookAuthIntent() }
    }

    @Test
    fun tappingOutlook_whenTheIntentCannotBeBuilt_reportsLaunchFailure() {
        val vm = viewModel()
        every { vm.outlookAuthIntent() } returns Result.failure(ActivityNotFoundException("no browser"))
        setContent(vm)

        composeTestRule.onNodeWithText(string(R.string.account_setup_outlook)).performClick()

        verify { vm.onOutlookLaunchFailed(any()) }
    }

    @Test
    fun doneStatus_reportsTheNewAccountIdToOnAccountAdded() {
        var addedId: String? = null
        setContent(
            viewModel(AccountSetupUiState(status = SetupStatus.DONE, addedAccountId = "outlook:me@outlook.com")),
            onAccountAdded = { addedId = it },
        )

        composeTestRule.waitUntil(5_000) { addedId != null }
        assertEquals("outlook:me@outlook.com", addedId)
    }

    @Test
    fun anError_isSurfacedAsASnackbar() {
        setContent(viewModel(AccountSetupUiState(error = "Microsoft sign-in failed")))

        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("Microsoft sign-in failed").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Microsoft sign-in failed").assertIsDisplayed()
    }

    @Test
    fun connectingStatus_showsTheBusyOverlaySpinner() {
        setContent(viewModel(AccountSetupUiState(status = SetupStatus.CONNECTING)))

        composeTestRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
    }

    @Test
    fun imapDisabledPrompt_showsTheDialog_andGotItDismisses() {
        // Outlook OAuth can succeed while IMAP is off, surfacing the actionable dialog (#390) instead
        // of the generic error snackbar; "Got it" clears the prompt via the view-model.
        val vm = viewModel(AccountSetupUiState(imapDisabledPrompt = ImapDisabledPrompt("Outlook", helpUrl = null)))
        setContent(vm)

        composeTestRule.onNodeWithText(string(R.string.imap_disabled_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.imap_disabled_dismiss)).performClick()

        verify { vm.dismissImapDisabledPrompt() }
    }
}
