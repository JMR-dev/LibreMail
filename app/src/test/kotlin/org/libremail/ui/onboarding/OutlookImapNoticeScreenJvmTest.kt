// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.onboarding

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
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
import org.libremail.ui.accountsetup.AccountSetupUiState
import org.libremail.ui.accountsetup.AccountSetupViewModel
import org.libremail.ui.accountsetup.SetupStatus
import org.libremail.ui.theme.LibreMailTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric JVM Compose test for the pre-auth Outlook IMAP-enablement notice (#411). Drives the
 * real [OutlookImapNoticeScreen] on the JVM via the v2 `createComposeRule()` under
 * [RobolectricTestRunner] — no emulator — so the screen counts toward JaCoCo's JVM-testable surface.
 * The instrumented [OutlookImapNoticeScreenTest] stays as the on-device E2E (with Espresso-Intents it
 * owns the real browser/AppAuth launch this JVM rule cannot safely surface).
 *
 * [AccountSetupViewModel] is mocked (its own logic is covered by `AccountSetupViewModelTest`); a
 * recording [UriHandler] captures the help/settings link launches and a no-op
 * [ActivityResultRegistry] lets the "Sign in" launcher register/launch without a real activity.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// A tall display so the whole scrolling column fits Robolectric's small default viewport, keeping
// every control on-screen for assertIsDisplayed / performClick without scrolling.
@Config(sdk = [36], qualifiers = "+w411dp-h2000dp")
class OutlookImapNoticeScreenJvmTest {

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

    /** A no-op registry so the "Sign in" launcher can register/launch without a real activity. */
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

    /** Records outbound link launches so the screen's help/settings links are exercised. */
    private val openedUrls = mutableListOf<String>()
    private val recordingUriHandler = object : UriHandler {
        override fun openUri(uri: String) {
            openedUrls.add(uri)
        }
    }

    /** A handler that always fails, to drive the "couldn't open your browser" snackbar path. */
    private val throwingUriHandler = object : UriHandler {
        override fun openUri(uri: String): Unit = throw ActivityNotFoundException("no browser")
    }

    private fun viewModel(state: AccountSetupUiState = AccountSetupUiState()): AccountSetupViewModel {
        val vm = mockk<AccountSetupViewModel>(relaxed = true)
        every { vm.state } returns MutableStateFlow(state)
        return vm
    }

    private fun setContent(
        viewModel: AccountSetupViewModel,
        uriHandler: UriHandler = recordingUriHandler,
        onBack: () -> Unit = {},
        onAccountAdded: (String) -> Unit = {},
    ) {
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalLifecycleOwner provides resumedOwner,
                LocalActivityResultRegistryOwner provides noopRegistryOwner,
                LocalUriHandler provides uriHandler,
            ) {
                LibreMailTheme(darkTheme = false, dynamicColor = false) {
                    OutlookImapNoticeScreen(
                        onBack = onBack,
                        onAccountAdded = onAccountAdded,
                        viewModel = viewModel,
                    )
                }
            }
        }
    }

    @Test
    fun rendersImapQuestion_bothLinks_andSignIn() {
        setContent(viewModel())

        composeTestRule.onNodeWithText(string(R.string.outlook_imap_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.outlook_imap_question)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.outlook_imap_body)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.outlook_imap_help)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.outlook_imap_settings)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.outlook_imap_sign_in)).assertIsDisplayed()
    }

    @Test
    fun tappingHelpLink_opensTheMicrosoftArticle() {
        setContent(viewModel())

        composeTestRule.onNodeWithText(string(R.string.outlook_imap_help)).performClick()

        assertTrue(
            "Help link must open a support.microsoft.com article",
            openedUrls.any { it.startsWith("https://support.microsoft.com/") },
        )
    }

    @Test
    fun tappingSettingsLink_opensOutlookSettings() {
        setContent(viewModel())

        composeTestRule.onNodeWithText(string(R.string.outlook_imap_settings)).performClick()

        assertTrue(
            "Settings link must open an outlook.live.com settings page",
            openedUrls.any { it.startsWith("https://outlook.live.com/") },
        )
    }

    @Test
    fun tappingSignIn_buildsTheAuthIntentAndLaunchesIt() {
        val vm = viewModel()
        // A real (empty) Intent is safe here: the no-op registry never actually starts it.
        every { vm.outlookAuthIntent() } returns Result.success(Intent())
        setContent(vm)

        composeTestRule.onNodeWithText(string(R.string.outlook_imap_sign_in)).performClick()

        verify { vm.outlookAuthIntent() }
    }

    @Test
    fun tappingSignIn_whenTheIntentCannotBeBuilt_reportsLaunchFailure() {
        val vm = viewModel()
        every { vm.outlookAuthIntent() } returns Result.failure(ActivityNotFoundException("no browser"))
        setContent(vm)

        composeTestRule.onNodeWithText(string(R.string.outlook_imap_sign_in)).performClick()

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
    fun connectingStatus_showsBusySpinner_andDisablesSignIn() {
        setContent(viewModel(AccountSetupUiState(status = SetupStatus.CONNECTING)))

        composeTestRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.outlook_imap_sign_in)).assertIsNotEnabled()
    }

    @Test
    fun tappingBack_invokesOnBack() {
        var backed = false
        setContent(viewModel(), onBack = { backed = true })

        composeTestRule.onNodeWithContentDescription(string(R.string.action_back)).performClick()

        assertTrue(backed)
    }

    @Test
    fun openingALink_whenNoBrowser_surfacesTheOpenFailedSnackbar() {
        setContent(viewModel(), uriHandler = throwingUriHandler)

        composeTestRule.onNodeWithText(string(R.string.outlook_imap_help)).performClick()

        val openFailed = string(R.string.app_password_open_failed)
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText(openFailed).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(openFailed).assertIsDisplayed()
    }
}
