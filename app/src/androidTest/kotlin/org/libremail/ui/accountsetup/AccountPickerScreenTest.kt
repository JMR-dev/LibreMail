// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.accountsetup

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.openid.appauth.AuthorizationManagementActivity
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
 * routes to [onManualSetup]. Tapping Outlook is covered separately, below, guarded by
 * Espresso-Intents so no real browser ever opens.
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

    /**
     * Tapping Outlook must fire the browser-launch intent for Microsoft sign-in (#276).
     * [OutlookAuthManager.createAuthIntent] delegates to AppAuth, whose
     * `AuthorizationService.getAuthorizationRequestIntent()` never returns a bare browser intent: it
     * always wraps it in an intent targeting AppAuth's own [AuthorizationManagementActivity], which
     * only starts the actual browser/Custom Tab once it resumes. That component name is therefore the
     * one characteristic of the launch that's both guaranteed (every Outlook tap goes through it) and
     * stable (it doesn't depend on which browser, if any, is installed on the test device) — matching
     * it both confirms the tap started the AppAuth authorization flow and, by stubbing a canceled
     * result, stops [AuthorizationManagementActivity] from ever resuming and opening a real browser.
     * The OAuth redirect, token exchange, and account creation a real sign-in would trigger next are
     * deliberately out of scope here (see #276).
     */
    @Test
    fun tappingOutlook_launchesTheAppAuthBrowserIntent() {
        setContent()

        Intents.init()
        try {
            Intents.intending(hasComponent(AuthorizationManagementActivity::class.java.name))
                .respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))

            composeTestRule.onNodeWithText(string(R.string.account_setup_outlook))
                .performScrollTo()
                .performClick()

            Intents.intended(hasComponent(AuthorizationManagementActivity::class.java.name))
        } finally {
            Intents.release()
        }
    }
}
