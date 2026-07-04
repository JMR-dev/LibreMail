// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.accountsetup

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.CoreMatchers.allOf
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
 * "create an app password" help link fires the browser intent. That launch is asserted with
 * Espresso-Intents (mirroring `AccountPickerScreenTest`'s Outlook test), so no real browser opens.
 */
@RunWith(AndroidJUnit4::class)
class AppPasswordSetupScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val provider = MailProvider.GMAIL

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
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                AppPasswordSetupScreen(onBack = {}, onAccountAdded = onAccountAdded, viewModel = viewModel)
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
     * Tapping the "create an app password" link opens the provider's help page via
     * [androidx.compose.ui.platform.UriHandler], which starts an `ACTION_VIEW` intent. Stubbing that
     * intent both proves the tap launched it and stops a real browser from opening on the device.
     */
    @Test
    fun tappingCreateAppPasswordPage_launchesBrowserIntentToHelpUrl() {
        setContent()

        Intents.init()
        try {
            Intents.intending(hasAction(Intent.ACTION_VIEW))
                .respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))

            composeTestRule.onNodeWithText(string(R.string.app_password_open_page, provider.displayName))
                .performScrollTo()
                .performClick()

            Intents.intended(allOf(hasAction(Intent.ACTION_VIEW), hasData(provider.appPasswordHelpUrl)))
        } finally {
            Intents.release()
        }
    }
}
