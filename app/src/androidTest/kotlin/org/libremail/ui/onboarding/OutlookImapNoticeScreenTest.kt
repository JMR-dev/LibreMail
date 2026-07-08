// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.onboarding

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import androidx.test.espresso.intent.matcher.UriMatchers.hasHost
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.openid.appauth.AuthorizationManagementActivity
import org.hamcrest.CoreMatchers.allOf
import org.hamcrest.CoreMatchers.equalTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.R
import org.libremail.auth.OutlookAuthManager
import org.libremail.ui.FakeAccountRepository
import org.libremail.ui.accountsetup.AccountSetupViewModel
import org.libremail.ui.theme.LibreMailTheme

/**
 * End-to-end UI test for the pre-auth Outlook IMAP-enablement notice (#411). Drives the real
 * [OutlookImapNoticeScreen] + [AccountSetupViewModel] over a [FakeAccountRepository]: the IMAP
 * question and both outbound links render, tapping a help link fires the browser `ACTION_VIEW`
 * intent, and tapping the bottom "Sign in" button starts the existing Microsoft OAuth (AppAuth)
 * flow. Both launches are asserted with Espresso-Intents (mirroring `AccountPickerScreenTest`), so no
 * real browser ever opens; the interstitial → OAuth navigation in the full onboarding graph is
 * covered by `OnboardingFlowTest`.
 */
@RunWith(AndroidJUnit4::class)
class OutlookImapNoticeScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    private fun string(resId: Int) = composeTestRule.activity.getString(resId)

    private fun setContent(onAccountAdded: (String) -> Unit = {}) {
        val viewModel = AccountSetupViewModel(OutlookAuthManager(context), FakeAccountRepository())
        composeTestRule.setContent {
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                OutlookImapNoticeScreen(onBack = {}, onAccountAdded = onAccountAdded, viewModel = viewModel)
            }
        }
    }

    @Test
    fun showsImapQuestion_bothLinks_andSignIn() {
        setContent()

        composeTestRule.onNodeWithText(string(R.string.outlook_imap_question)).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.outlook_imap_help)).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.outlook_imap_settings)).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.outlook_imap_sign_in)).performScrollTo().assertIsDisplayed()
    }

    /**
     * Tapping the "How to enable IMAP" link opens Microsoft's help article via
     * [androidx.compose.ui.platform.UriHandler], which starts an `ACTION_VIEW` intent. Stubbing that
     * intent both proves the tap launched it and stops a real browser from opening on the device.
     */
    @Test
    fun tappingImapHelpLink_opensTheMicrosoftArticle() {
        setContent()

        Intents.init()
        try {
            Intents.intending(hasAction(Intent.ACTION_VIEW))
                .respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))

            composeTestRule.onNodeWithText(string(R.string.outlook_imap_help))
                .performScrollTo()
                .performClick()

            Intents.intended(allOf(hasAction(Intent.ACTION_VIEW), hasData(hasHost(equalTo("support.microsoft.com")))))
        } finally {
            Intents.release()
        }
    }

    /**
     * Tapping "Sign in" must continue the existing Outlook OAuth flow — i.e. fire the AppAuth
     * authorization intent (mirroring `AccountPickerScreenTest.tappingOutlook_...`). AppAuth wraps its
     * browser launch in an intent targeting [AuthorizationManagementActivity]; that component name is
     * the guaranteed, browser-independent signature of the launch. Stubbing a canceled result stops
     * that activity from ever resuming and opening a real browser.
     */
    @Test
    fun tappingSignIn_launchesTheAppAuthBrowserIntent() {
        setContent()

        Intents.init()
        try {
            Intents.intending(hasComponent(AuthorizationManagementActivity::class.java.name))
                .respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))

            composeTestRule.onNodeWithText(string(R.string.outlook_imap_sign_in))
                .performScrollTo()
                .performClick()

            Intents.intended(hasComponent(AuthorizationManagementActivity::class.java.name))
        } finally {
            Intents.release()
        }
    }
}
