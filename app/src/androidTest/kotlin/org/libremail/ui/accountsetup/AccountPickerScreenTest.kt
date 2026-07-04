// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.accountsetup

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.R
import org.libremail.auth.OutlookAuthManager
import org.libremail.domain.model.MailProvider
import org.libremail.ui.FakeAccountRepository
import org.libremail.ui.theme.LibreMailTheme

/**
 * End-to-end UI test for the account-vendor picker used by onboarding and "Add account". Drives the
 * real [AccountPickerScreen] + [AccountSetupViewModel]: every setup choice is listed, tapping an
 * app-password vendor routes to [onPickProvider] with that [MailProvider], and tapping "Other"
 * routes to [onManualSetup]. The Outlook row is deliberately not tapped (it launches a browser).
 */
@RunWith(AndroidJUnit4::class)
class AccountPickerScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    private fun string(resId: Int) = composeTestRule.activity.getString(resId)

    private fun setContent(onPickProvider: (MailProvider) -> Unit = {}, onManualSetup: () -> Unit = {}) {
        val viewModel = AccountSetupViewModel(OutlookAuthManager(context), FakeAccountRepository())
        composeTestRule.setContent {
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                AccountPickerScreen(
                    onBack = {},
                    onAccountAdded = {},
                    onPickProvider = onPickProvider,
                    onManualSetup = onManualSetup,
                    viewModel = viewModel,
                )
            }
        }
    }

    @Test
    fun allSetupChoices_areListed() {
        setContent()

        composeTestRule.onNodeWithText(string(R.string.account_setup_outlook)).performScrollTo().assertIsDisplayed()
        MailProvider.entries.forEach { provider ->
            composeTestRule.onNodeWithText(provider.displayName).performScrollTo().assertIsDisplayed()
        }
        composeTestRule.onNodeWithText(string(R.string.account_setup_other)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun tappingAppPasswordProvider_invokesOnPickProvider() {
        var picked: MailProvider? = null
        setContent(onPickProvider = { picked = it })

        composeTestRule.onNodeWithText(MailProvider.GMAIL.displayName).performScrollTo().performClick()

        composeTestRule.waitUntil(5_000) { picked == MailProvider.GMAIL }
    }

    @Test
    fun tappingOther_invokesOnManualSetup() {
        var manualRequested = false
        setContent(onManualSetup = { manualRequested = true })

        composeTestRule.onNodeWithText(string(R.string.account_setup_other)).performScrollTo().performClick()

        composeTestRule.waitUntil(5_000) { manualRequested }
    }
}
