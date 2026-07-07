// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.accountsetup

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
 * Robolectric JVM Compose test (#378, umbrella #373) for the guided app-password setup screen (#29).
 * Mirrors the instrumented [AppPasswordSetupScreenTest] on the JVM via the v2 `createComposeRule()`
 * under [RobolectricTestRunner] — no emulator — so [AppPasswordSetupScreen] counts toward JaCoCo's
 * JVM-testable surface (this file is dropped from `jacocoNonJvmTestableSurface`). The instrumented
 * test stays as the on-device E2E.
 *
 * [AppPasswordViewModel] is mocked (its own logic is covered by [AppPasswordViewModelTest]); this
 * exercises the screen's per-provider chrome, the unknown-provider fallback, the credential-field and
 * help-link wiring, and the enabled/busy/error/done branches. A recording [UriHandler] captures the
 * help-link launches instead of opening a real browser (mirroring the instrumented Espresso-Intents
 * assertion).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// A tall display so the whole scrolling setup form fits Robolectric's small default viewport,
// keeping every control on-screen for assertIsDisplayed / performClick without scrolling.
@Config(sdk = [36], qualifiers = "+w411dp-h2000dp")
class AppPasswordSetupScreenJvmTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun string(resId: Int, vararg args: Any): String = context.getString(resId, *args)

    /** RESUMED owner so `collectAsStateWithLifecycle` collects the form state. */
    private val resumedOwner = object : LifecycleOwner {
        private val registry =
            LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    /** Records outbound help-link launches so the screen's `openUrl` is exercised without a browser. */
    private val openedUrls = mutableListOf<String>()
    private val recordingUriHandler = object : UriHandler {
        override fun openUri(uri: String) {
            openedUrls.add(uri)
        }
    }

    private fun viewModel(
        provider: MailProvider? = MailProvider.GMAIL,
        form: AppPasswordForm = AppPasswordForm(),
    ): AppPasswordViewModel {
        val vm = mockk<AppPasswordViewModel>(relaxed = true)
        every { vm.provider } returns provider
        every { vm.form } returns MutableStateFlow(form)
        return vm
    }

    private fun setContent(viewModel: AppPasswordViewModel, onAccountAdded: (String) -> Unit = {}) {
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalLifecycleOwner provides resumedOwner,
                LocalUriHandler provides recordingUriHandler,
            ) {
                LibreMailTheme(darkTheme = false, dynamicColor = false) {
                    AppPasswordSetupScreen(onBack = {}, onAccountAdded = onAccountAdded, viewModel = viewModel)
                }
            }
        }
    }

    @Test
    fun rendersGmailChrome_credentialFields_andDisabledSubmit() {
        setContent(viewModel(MailProvider.GMAIL))

        composeTestRule.onNodeWithText(string(R.string.app_password_title, "Gmail")).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.app_password_what_is)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.app_password_warning)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.app_password_2fa_help)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.app_password_open_page, "Gmail")).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.app_password_email)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.app_password_field)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.app_password_show_servers)).assertIsDisplayed()
        // Blank credentials -> the form is invalid, so the submit button starts disabled.
        composeTestRule.onNodeWithText(string(R.string.app_password_test_and_add)).assertIsNotEnabled()
    }

    @Test
    fun icloudChrome_usesTheAppleTwoFactorLabel() {
        setContent(viewModel(MailProvider.ICLOUD))

        composeTestRule.onNodeWithText(string(R.string.app_password_title, "iCloud Mail")).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.app_password_2fa_help_icloud)).assertIsDisplayed()
    }

    @Test
    fun yahooChrome_hasNoTwoFactorPrerequisiteLink() {
        setContent(viewModel(MailProvider.YAHOO))

        composeTestRule.onNodeWithText(string(R.string.app_password_title, "Yahoo Mail")).assertIsDisplayed()
        // Yahoo has no twoFactorHelpUrl, so neither two-factor button variant is shown.
        composeTestRule.onNodeWithText(string(R.string.app_password_2fa_help)).assertDoesNotExist()
        composeTestRule.onNodeWithText(string(R.string.app_password_2fa_help_icloud)).assertDoesNotExist()
    }

    @Test
    fun aolChrome_rendersItsTitleWithoutTwoFactorLink() {
        setContent(viewModel(MailProvider.AOL))

        composeTestRule.onNodeWithText(string(R.string.app_password_title, "AOL Mail")).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.app_password_2fa_help)).assertDoesNotExist()
    }

    @Test
    fun unknownProvider_showsTheFallbackAndNoForm() {
        setContent(viewModel(provider = null))

        composeTestRule.onNodeWithText(string(R.string.app_password_unknown_provider)).assertIsDisplayed()
        // The defensive early-return means the credential form is never rendered.
        composeTestRule.onNodeWithText(string(R.string.app_password_email)).assertDoesNotExist()
    }

    @Test
    fun typingCredentials_forwardsToTheViewModel() {
        val vm = viewModel(MailProvider.GMAIL)
        setContent(vm)

        composeTestRule.onNodeWithText(string(R.string.app_password_email)).performTextInput("me@gmail.com")
        composeTestRule.onNodeWithText(string(R.string.app_password_field)).performTextInput("app-pw-1234")

        verify { vm.onEmail(any()) }
        verify { vm.onAppPassword(any()) }
    }

    @Test
    fun validCredentials_enableSubmit_andTapCallsTestAndSave() {
        val vm = viewModel(MailProvider.GMAIL, AppPasswordForm(email = "me@gmail.com", appPassword = "app-pw"))
        setContent(vm)

        composeTestRule.onNodeWithText(string(R.string.app_password_test_and_add)).assertIsEnabled().performClick()

        verify { vm.testAndSave() }
    }

    @Test
    fun connectingStatus_disablesSubmit() {
        setContent(
            viewModel(
                MailProvider.GMAIL,
                AppPasswordForm(email = "me@gmail.com", appPassword = "app-pw", status = SetupStatus.CONNECTING),
            ),
        )

        composeTestRule.onNodeWithText(string(R.string.app_password_test_and_add)).assertIsNotEnabled()
    }

    @Test
    fun doneStatus_reportsTheNewAccountIdToOnAccountAdded() {
        var addedId: String? = null
        setContent(
            viewModel(
                MailProvider.GMAIL,
                AppPasswordForm(status = SetupStatus.DONE, addedAccountId = "imap:me@gmail.com"),
            ),
            onAccountAdded = { addedId = it },
        )

        composeTestRule.waitUntil(5_000) { addedId != null }
        assertEquals("imap:me@gmail.com", addedId)
    }

    @Test
    fun anError_isSurfacedAsASnackbar() {
        setContent(viewModel(MailProvider.GMAIL, AppPasswordForm(error = "Login failed")))

        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("Login failed").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Login failed").assertIsDisplayed()
    }

    @Test
    fun expandedAdvanced_showsPresetServers_andToggleCallsViewModel() {
        val vm = viewModel(MailProvider.GMAIL, AppPasswordForm(advancedExpanded = true))
        setContent(vm)

        composeTestRule.onNodeWithText(string(R.string.app_password_show_servers)).performClick()
        verify { vm.toggleAdvanced() }

        // Expanded, the read-only preset servers for the vendor are shown.
        composeTestRule.onNodeWithText("imap.gmail.com", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("smtp.gmail.com", substring = true).assertIsDisplayed()
    }

    @Test
    fun tappingHelpLinks_opensTheProviderTwoFactorAndAppPasswordPages() {
        setContent(viewModel(MailProvider.GMAIL))

        composeTestRule.onNodeWithText(string(R.string.app_password_open_page, "Gmail")).performClick()
        composeTestRule.onNodeWithText(string(R.string.app_password_2fa_help)).performClick()

        assertTrue(openedUrls.contains(MailProvider.GMAIL.appPasswordHelpUrl))
        assertTrue(openedUrls.contains(MailProvider.GMAIL.twoFactorHelpUrl))
    }
}
