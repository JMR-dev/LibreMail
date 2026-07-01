// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.onboarding

import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.R
import org.libremail.auth.OutlookAuthManager
import org.libremail.data.settings.SettingsRepository
import org.libremail.domain.model.Message
import org.libremail.push.BatteryOptimizationManager
import org.libremail.ui.FakeAccountRepository
import org.libremail.ui.FakeMailRepository
import org.libremail.ui.FakeMailSyncer
import org.libremail.ui.accountsetup.AccountPickerScreen
import org.libremail.ui.accountsetup.AccountSetupViewModel
import org.libremail.ui.accountsetup.AppPasswordSetupScreen
import org.libremail.ui.accountsetup.AppPasswordViewModel
import org.libremail.ui.mailbox.MailboxScreen
import org.libremail.ui.mailbox.MailboxViewModel
import org.libremail.ui.navigation.Routes
import org.libremail.ui.theme.LibreMailTheme

/**
 * End-to-end test of the onboarding flow: a fresh install (no accounts) walks welcome → vendor
 * picker → app-password setup → "add another?" → the first account's inbox.
 *
 * It drives the real onboarding screens + ViewModels through a real [NavHost]. The account backend is
 * the in-memory [FakeAccountRepository] (a successful add makes the account observable) rather than a
 * live server — GreenMail-backed connection behaviour is covered by the repository unit tests; this
 * test owns the cross-screen navigation contract.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingFlowTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun string(resId: Int) = composeTestRule.activity.getString(resId)

    // Generous cap for the slow, animation-disabled CI matrix emulators; waitUntil returns as soon
    // as the text appears, so the happy path is unaffected.
    private fun waitForText(text: String) = composeTestRule.waitUntil(15_000) {
        composeTestRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun inboxMessage() = Message(
        id = "imap:e2e@gmail.com:INBOX:1",
        accountId = "imap:e2e@gmail.com",
        sender = "Welcome",
        senderEmail = "welcome@gmail.com",
        subject = "E2E first message",
        snippet = "",
        body = "",
        isHtml = false,
        timestampMillis = 1_000L,
        isRead = false,
        isStarred = false,
        folder = "INBOX",
        inInbox = true,
        bodyFetched = false,
    )

    private fun setOnboardingContent(accountRepo: FakeAccountRepository, mailRepo: FakeMailRepository) {
        // Real collaborators are cheap here: the manager just wraps PowerManager and the repository
        // reads the on-device settings DataStore. This test drives its own nav graph (without the
        // battery step), so the onboarding view model's battery decision is inert for this flow.
        val appContext = composeTestRule.activity.applicationContext
        val onboarding = OnboardingViewModel(
            BatteryOptimizationManager(appContext),
            SettingsRepository(appContext),
        )
        composeTestRule.setContent {
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                val navController = rememberNavController()
                val context = LocalContext.current
                val outlookAuthManager = remember { OutlookAuthManager(context) }
                NavHost(navController = navController, startDestination = Routes.ONBOARDING_WELCOME) {
                    composable(Routes.ONBOARDING_WELCOME) {
                        OnboardingWelcomeScreen(
                            onAddAccount = { navController.navigate(Routes.ONBOARDING_PICKER) },
                        )
                    }
                    composable(Routes.ONBOARDING_PICKER) {
                        val viewModel = remember { AccountSetupViewModel(outlookAuthManager, accountRepo) }
                        AccountPickerScreen(
                            onBack = {},
                            onAccountAdded = { id ->
                                onboarding.onAccountAdded(id)
                                navController.navigate(Routes.ONBOARDING_ADD_ANOTHER)
                            },
                            onPickProvider = { provider ->
                                navController.navigate(Routes.onboardingAppPassword(provider.key))
                            },
                            onManualSetup = {},
                            viewModel = viewModel,
                        )
                    }
                    composable(
                        route = Routes.ONBOARDING_APP_PASSWORD_PATTERN,
                        arguments = listOf(
                            navArgument(Routes.APP_PASSWORD_ARG_PROVIDER) { type = NavType.StringType },
                        ),
                    ) { entry ->
                        val key = entry.arguments?.getString(Routes.APP_PASSWORD_ARG_PROVIDER).orEmpty()
                        val viewModel = remember {
                            AppPasswordViewModel(
                                SavedStateHandle(mapOf(Routes.APP_PASSWORD_ARG_PROVIDER to key)),
                                accountRepo,
                            )
                        }
                        AppPasswordSetupScreen(
                            onBack = {},
                            onAccountAdded = { id ->
                                onboarding.onAccountAdded(id)
                                navController.navigate(Routes.ONBOARDING_ADD_ANOTHER) {
                                    popUpTo(Routes.ONBOARDING_PICKER)
                                }
                            },
                            viewModel = viewModel,
                        )
                    }
                    composable(Routes.ONBOARDING_ADD_ANOTHER) {
                        AddAnotherAccountScreen(
                            onAddAnother = {
                                navController.navigate(Routes.ONBOARDING_PICKER) {
                                    popUpTo(Routes.ONBOARDING_PICKER) { inclusive = true }
                                }
                            },
                            onFinish = {
                                val id = onboarding.firstAddedAccountId
                                val dest = if (id != null) Routes.mailboxForAccount(id) else Routes.MAILBOX
                                navController.navigate(dest) {
                                    popUpTo(Routes.ONBOARDING_WELCOME) { inclusive = true }
                                }
                            },
                        )
                    }
                    composable(
                        route = Routes.MAILBOX_PATTERN,
                        arguments = listOf(
                            navArgument(Routes.MAILBOX_ARG_ACCOUNT) {
                                type = NavType.StringType
                                defaultValue = ""
                            },
                        ),
                    ) { entry ->
                        val account = entry.arguments?.getString(Routes.MAILBOX_ARG_ACCOUNT).orEmpty()
                        val viewModel = remember {
                            MailboxViewModel(
                                mailRepo,
                                accountRepo,
                                FakeMailSyncer(),
                                SavedStateHandle(mapOf(Routes.MAILBOX_ARG_ACCOUNT to account)),
                            )
                        }
                        MailboxScreen(
                            onOpenMessage = {},
                            onCompose = {},
                            onOpenDrafts = {},
                            onOpenOutbox = {},
                            onAddAccount = {},
                            onOpenCompose = {},
                            onSelectTab = {},
                            viewModel = viewModel,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun onboarding_addsAppPasswordAccount_thenLandsOnFirstAccountInbox() {
        val accountRepo = FakeAccountRepository()
        val mailRepo = FakeMailRepository(messages = listOf(inboxMessage()))
        setOnboardingContent(accountRepo, mailRepo)

        // Welcome → picker.
        composeTestRule.onNodeWithText(string(R.string.onboarding_welcome_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.onboarding_add_account)).performClick()

        // Picker → Gmail app-password setup.
        waitForText("Gmail")
        composeTestRule.onNodeWithText("Gmail").performClick()

        // App-password setup: email + app password come from the user; servers come from the preset.
        // performScrollTo first — on the short default matrix emulator the fields and the "Test and
        // add" button sit below the fold of this scrolling screen, and a positional click on an
        // off-screen button is a silent no-op (which is why this passed only on API 37's taller AVD).
        waitForText(string(R.string.app_password_email))
        composeTestRule.onNodeWithText(string(R.string.app_password_email))
            .performScrollTo().performTextInput("e2e@gmail.com")
        composeTestRule.onNodeWithText(string(R.string.app_password_field))
            .performScrollTo().performTextInput("app-pass")
        composeTestRule.onNodeWithText(string(R.string.app_password_test_and_add))
            .performScrollTo().performClick()

        // "Add another?" prompt → No.
        waitForText(string(R.string.onboarding_add_another_prompt))
        composeTestRule.onNodeWithText(string(R.string.onboarding_add_another_no)).performClick()

        // Landed on the first (and only) account's inbox.
        waitForText("E2E first message")
        composeTestRule.onNodeWithText("E2E first message").assertIsDisplayed()
    }
}
