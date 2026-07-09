// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.accountsetup

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import jakarta.mail.AuthenticationFailedException
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.R
import org.libremail.domain.model.MailProvider
import org.libremail.ui.FakeAccountRepository
import org.libremail.ui.navigation.Routes
import org.libremail.ui.theme.LibreMailTheme

/**
 * End-to-end UI test for the guided app-password setup screen (#29). Drives the real
 * [AppPasswordSetupScreen] + [AppPasswordViewModel] over a [FakeAccountRepository] for the preset
 * Gmail vendor: the provider-specific chrome renders, entering an email + app password and tapping
 * "Test & add" persists through the repository and reports the new account id, and tapping the
 * "create an app password" help link opens the provider's help page.
 *
 * The outbound help links are verified by injecting a recording [UriHandler] for [LocalUriHandler]
 * and asserting the URL the screen asked to open — deliberately NOT via Espresso-Intents. The two
 * approaches verify the same behaviour, but `Intents.intended(...)` runs an `onView(isRoot())` view
 * assertion whose `RootViewPicker` waits up to 10s for a window-focused root; on the CI emulator the
 * activity window intermittently reports `has-window-focus=false`, so that assertion flakes with
 * `RootViewWithoutFocusException` (an infra flake that fails every `intended()`-based E2E test on the
 * affected leg and forces a costly 9-min retry). Driving the link through a fake [UriHandler] keeps
 * the whole test on Compose interactions, which do not depend on window focus, so it is deterministic
 * — while still asserting the exact provider page the tap opens.
 */
@RunWith(AndroidJUnit4::class)
class AppPasswordSetupScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val provider = MailProvider.GMAIL

    // Captures the URL the screen hands to LocalUriHandler instead of launching a real browser.
    private val uriHandler = RecordingUriHandler()

    private fun string(resId: Int, vararg args: Any) = composeTestRule.activity.getString(resId, *args)

    private fun setContent(
        repository: FakeAccountRepository = FakeAccountRepository(),
        onAccountAdded: (String) -> Unit = {},
    ) {
        val viewModel = AppPasswordViewModel(
            SavedStateHandle(mapOf(Routes.APP_PASSWORD_ARG_PROVIDER to provider.key)),
            repository,
        )
        composeTestRule.setContent {
            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                LibreMailTheme(darkTheme = false, dynamicColor = false) {
                    AppPasswordSetupScreen(onBack = {}, onAccountAdded = onAccountAdded, viewModel = viewModel)
                }
            }
        }
    }

    @Test
    fun rendersProviderTitle_andCredentialFields() {
        setContent()

        composeTestRule.onNodeWithText(string(R.string.app_password_title, provider.displayName)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.app_password_email)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.app_password_field)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.app_password_test_and_add)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun enteringCredentials_andTappingTestAndAdd_addsAccountAndReportsId() {
        val repository = FakeAccountRepository()
        var addedId: String? = null
        setContent(repository = repository, onAccountAdded = { addedId = it })

        composeTestRule.onNodeWithText(string(R.string.app_password_email)).performTextInput("me@gmail.com")
        composeTestRule.onNodeWithText(string(R.string.app_password_field)).performTextInput("app-pw-1234")
        composeTestRule.onNodeWithText(string(R.string.app_password_test_and_add)).performScrollTo().performClick()

        // A successful add persists via the repository and hands the new account id to the caller.
        composeTestRule.waitUntil(5_000) {
            repository.addedAccount?.email == "me@gmail.com" && addedId == "imap:me@gmail.com"
        }
    }

    /**
     * Tapping the "create an app password" link opens the provider's help page via [LocalUriHandler].
     * Asserting the URL captured by [RecordingUriHandler] proves the tap requested the right page
     * without launching a real browser (and without the window-focus-dependent Espresso-Intents
     * assertion that flakes on CI — see the class comment).
     */
    @Test
    fun tappingCreateAppPasswordPage_launchesBrowserIntentToHelpUrl() {
        setContent()

        composeTestRule.onNodeWithText(string(R.string.app_password_open_page, provider.displayName))
            .performScrollTo()
            .performClick()

        assertEquals(provider.appPasswordHelpUrl, uriHandler.lastUri)
    }

    /**
     * When the connection test fails specifically because IMAP is disabled (Gmail's "not enabled for
     * IMAP use"), the screen surfaces the actionable "turn on IMAP" dialog instead of a generic error,
     * and its help link opens the provider's enable-IMAP page (#390). Driving the failure through a
     * [FakeAccountRepository] exercises the real classification + dialog wiring end to end on device;
     * the help link's target is verified through the injected [RecordingUriHandler] (see the class
     * comment for why not Espresso-Intents).
     */
    @Test
    fun imapDisabledFailure_showsThePrompt_andHelpLinkOpensTheProviderPage() {
        setContent(
            repository = FakeAccountRepository(
                result = Result.failure(
                    AuthenticationFailedException("Your account is not enabled for IMAP use"),
                ),
            ),
        )

        composeTestRule.onNodeWithText(string(R.string.app_password_email)).performTextInput("me@gmail.com")
        composeTestRule.onNodeWithText(string(R.string.app_password_field)).performTextInput("app-pw-1234")
        composeTestRule.onNodeWithText(string(R.string.app_password_test_and_add)).performScrollTo().performClick()

        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText(string(R.string.imap_disabled_title)).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(string(R.string.imap_disabled_message, provider.displayName)).assertIsDisplayed()

        composeTestRule.onNodeWithText(string(R.string.imap_disabled_help)).performClick()

        // Mirrors the previous Espresso hasHost(...) check: the Gmail enable-IMAP page is on Google's
        // support host. Verifying the exact host keeps the assertion strength without any focus wait.
        assertEquals("support.google.com", Uri.parse(uriHandler.lastUri).host)
    }

    /** A [UriHandler] that records the last opened URL instead of starting a real `ACTION_VIEW` intent. */
    private class RecordingUriHandler : UriHandler {
        var lastUri: String? = null
            private set

        override fun openUri(uri: String) {
            lastUri = uri
        }
    }
}
