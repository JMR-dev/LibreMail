// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.onboarding

import android.app.Activity
import android.app.Instrumentation
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.hamcrest.CoreMatchers.allOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.R
import org.libremail.contacts.ContactsPermissionManager
import org.libremail.data.settings.SettingsRepository
import org.libremail.push.BatteryOptimizationManager
import org.libremail.ui.navigation.Routes
import org.libremail.ui.theme.LibreMailTheme

/**
 * End-to-end test for the onboarding battery opt-in step (#49). It drives the real
 * [BatteryOptimizationScreen] + graph-scoped [OnboardingViewModel] through a NavHost that mirrors the
 * production "add another? → (optional) battery → inbox" tail (see
 * `LibreMailApp.onboardingFinishDestinations`).
 *
 * Battery status comes from the real [BatteryOptimizationManager]: a fresh emulator is never on the
 * battery allowlist, so the step is offered. The "already unrestricted" skip can't be forced from a
 * test (there's no API to set it) and is covered by the view-model unit tests; the "already handled"
 * skip is exercised here through the real settings DataStore.
 */
@RunWith(AndroidJUnit4::class)
class BatteryOptimizationStepTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var onboarding: OnboardingViewModel

    private fun string(resId: Int) = composeTestRule.activity.getString(resId)

    private fun waitForText(text: String) = composeTestRule.waitUntil(10_000) {
        composeTestRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    /**
     * Renders the "add another? → battery → inbox" tail with one account already added this session,
     * starting on the add-another prompt. [handled] seeds the persisted "prompt handled" flag so the
     * skip path can be exercised through the real repository.
     */
    private fun setContent(handled: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        settingsRepository = SettingsRepository(context)
        runBlocking { settingsRepository.setBatteryPromptHandled(handled) }
        onboarding = OnboardingViewModel(
            BatteryOptimizationManager(context),
            ContactsPermissionManager(context),
            settingsRepository,
            SavedStateHandle(),
        )
        onboarding.onAccountAdded(FIRST_ACCOUNT_ID)

        composeTestRule.setContent {
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = Routes.ONBOARDING_ADD_ANOTHER) {
                    composable(Routes.ONBOARDING_ADD_ANOTHER) {
                        val batteryPromptNeeded by onboarding.batteryPromptNeeded.collectAsStateWithLifecycle()
                        AddAnotherAccountScreen(
                            onAddAnother = {},
                            onFinish = {
                                if (batteryPromptNeeded == true) {
                                    navController.navigate(Routes.ONBOARDING_BATTERY)
                                } else {
                                    navController.navigate(Routes.mailboxForAccount(FIRST_ACCOUNT_ID)) {
                                        popUpTo(Routes.ONBOARDING_ADD_ANOTHER) { inclusive = true }
                                    }
                                }
                            },
                        )
                    }
                    composable(Routes.ONBOARDING_BATTERY) {
                        BatteryOptimizationScreen(
                            viewModel = onboarding,
                            onFinish = {
                                onboarding.markBatteryPromptHandled()
                                navController.navigate(Routes.mailboxForAccount(FIRST_ACCOUNT_ID)) {
                                    popUpTo(Routes.ONBOARDING_ADD_ANOTHER) { inclusive = true }
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
                    ) {
                        Text(INBOX_MARKER)
                    }
                }
            }
        }
    }

    @Test
    fun batteryStep_isOffered_thenNotNow_landsOnInbox() {
        setContent(handled = false)
        // The decision resolves asynchronously (a DataStore read); wait before driving the finish tap.
        composeTestRule.waitUntil(10_000) { onboarding.batteryPromptNeeded.value == true }

        composeTestRule.onNodeWithText(string(R.string.onboarding_add_another_no)).performClick()

        // The battery opt-in step is shown...
        waitForText(string(R.string.onboarding_battery_title))
        composeTestRule.onNodeWithText(string(R.string.onboarding_battery_title)).assertIsDisplayed()

        // ...and "Not now" continues to the inbox and records the prompt as handled (so it won't nag).
        composeTestRule.onNodeWithText(string(R.string.onboarding_battery_not_now)).performClick()
        waitForText(INBOX_MARKER)
        composeTestRule.onNodeWithText(INBOX_MARKER).assertIsDisplayed()
        composeTestRule.waitUntil(5_000) { runBlocking { settingsRepository.isBatteryPromptHandled() } }
    }

    @Test
    fun batteryStep_takeMeThere_opensThisAppsSystemSettings() {
        setContent(handled = false)
        composeTestRule.waitUntil(10_000) { onboarding.batteryPromptNeeded.value == true }
        composeTestRule.onNodeWithText(string(R.string.onboarding_add_another_no)).performClick()
        waitForText(string(R.string.onboarding_battery_title))

        val packageName = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        Intents.init()
        try {
            // Stub the match so the real system settings screen never actually launches mid-test.
            Intents.intending(hasAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS))
                .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))

            composeTestRule.onNodeWithText(string(R.string.onboarding_battery_take_me)).performClick()

            // Deep-links to *this app's* details screen (where Battery → Unrestricted lives).
            Intents.intended(
                allOf(
                    hasAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS),
                    hasData(Uri.fromParts("package", packageName, null)),
                ),
            )
        } finally {
            Intents.release()
        }
    }

    @Test
    fun finish_skipsBatteryStep_whenAlreadyHandled() {
        setContent(handled = true)
        composeTestRule.waitUntil(10_000) { onboarding.batteryPromptNeeded.value == false }

        composeTestRule.onNodeWithText(string(R.string.onboarding_add_another_no)).performClick()

        // Straight to the inbox — the opt-in step is skipped entirely.
        waitForText(INBOX_MARKER)
        composeTestRule.onNodeWithText(INBOX_MARKER).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.onboarding_battery_title)).assertDoesNotExist()
    }

    private companion object {
        const val INBOX_MARKER = "INBOX-REACHED"
        const val FIRST_ACCOUNT_ID = "imap:e2e@example.com"
    }
}
