// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.onboarding

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
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
import org.junit.Assert.assertEquals
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
 * question and both outbound links render, tapping the "How to enable IMAP" help link opens
 * Microsoft's help article, and tapping the bottom "Sign in" button starts the existing Microsoft
 * OAuth (AppAuth) flow.
 *
 * The help link is verified by injecting a recording [UriHandler] for [LocalUriHandler] and asserting
 * the opened URL — not via Espresso-Intents, whose `intended(...)` runs an `onView(isRoot())`
 * assertion that waits for a window-focused root and flakes with `RootViewWithoutFocusException` on
 * the CI emulator (see `AppPasswordSetupScreenTest` for the full write-up). The "Sign in" launch has
 * no [UriHandler] seam — AppAuth calls `startActivity` directly — so it stays on Espresso-Intents,
 * matched by AppAuth's [AuthorizationManagementActivity] component and stubbed so no real browser
 * opens; the interstitial → OAuth navigation in the full onboarding graph is covered by
 * `OnboardingFlowTest`.
 */
@RunWith(AndroidJUnit4::class)
class OutlookImapNoticeScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    // Captures the URL the screen hands to LocalUriHandler instead of launching a real browser.
    private val uriHandler = RecordingUriHandler()

    private fun string(resId: Int) = composeTestRule.activity.getString(resId)

    private fun setContent(onAccountAdded: (String) -> Unit = {}) {
        val viewModel = AccountSetupViewModel(OutlookAuthManager(context), FakeAccountRepository())
        composeTestRule.setContent {
            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                LibreMailTheme(darkTheme = false, dynamicColor = false) {
                    OutlookImapNoticeScreen(onBack = {}, onAccountAdded = onAccountAdded, viewModel = viewModel)
                }
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
     * Tapping the "How to enable IMAP" link opens Microsoft's help article via [LocalUriHandler].
     * Asserting the URL captured by [RecordingUriHandler] proves the tap requested the right page
     * without launching a real browser (and without the window-focus-dependent Espresso-Intents
     * assertion that flakes on CI — see the class comment).
     */
    @Test
    fun tappingImapHelpLink_opensTheMicrosoftArticle() {
        setContent()

        composeTestRule.onNodeWithText(string(R.string.outlook_imap_help))
            .performScrollTo()
            .performClick()

        assertEquals("support.microsoft.com", Uri.parse(uriHandler.lastUri).host)
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

    /** A [UriHandler] that records the last opened URL instead of starting a real `ACTION_VIEW` intent. */
    private class RecordingUriHandler : UriHandler {
        var lastUri: String? = null
            private set

        override fun openUri(uri: String) {
            lastUri = uri
        }
    }
}
