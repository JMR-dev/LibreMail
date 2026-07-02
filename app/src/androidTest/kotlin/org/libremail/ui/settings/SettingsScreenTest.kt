// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.R
import org.libremail.contacts.ContactsPermissionManager
import org.libremail.data.security.AppLockManager
import org.libremail.data.security.DatabaseKeyCipher
import org.libremail.data.security.DatabaseKeyStore
import org.libremail.data.security.KeystoreCrypto
import org.libremail.data.security.PassphraseSession
import org.libremail.data.settings.FetchPolicy
import org.libremail.data.settings.SettingsRepository
import org.libremail.data.sync.SyncScheduler
import org.libremail.push.BatteryOptimizationManager
import org.libremail.ui.FakeAccountRepository
import org.libremail.ui.theme.LibreMailTheme
import javax.inject.Provider

/**
 * End-to-end tests for the global settings screen. Tapping a policy must round-trip through the real
 * [SettingsRepository] (DataStore), proving the UI → ViewModel → persistence wiring; and enabling
 * app-lock on a device with no secure lock must surface the rejection message via a snackbar — now
 * visible to Compose semantics, unlike the former Toast it replaced.
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    // Device reports no secure lock, so enabling app-lock is rejected with a message.
    private val insecureDevice = object : AppLockManager {
        override fun isDeviceSecure() = false
    }

    private fun string(resId: Int) = composeTestRule.activity.getString(resId)

    private fun settingsViewModel(settingsRepository: SettingsRepository): SettingsViewModel {
        val keyStore = DatabaseKeyStore(context, KeystoreCrypto(), DatabaseKeyCipher(), PassphraseSession())
        return SettingsViewModel(
            FakeAccountRepository(),
            settingsRepository,
            insecureDevice,
            keyStore,
            BatteryOptimizationManager(context),
            ContactsPermissionManager(context),
            SyncScheduler(Provider { WorkManager.getInstance(context) }),
        )
    }

    private fun setContent(viewModel: SettingsViewModel) {
        composeTestRule.setContent {
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                SettingsScreen(
                    onAddAccount = {},
                    onOpenAccount = {},
                    onSelectTab = {},
                    onReportProblem = {},
                    viewModel = viewModel,
                )
            }
        }
    }

    @Test
    fun selectingFetchPolicy_persistsThroughTheRepository() {
        val settingsRepository = SettingsRepository(context)
        runBlocking { settingsRepository.setFetchPolicy(FetchPolicy.ALWAYS) } // known starting state
        setContent(settingsViewModel(settingsRepository))

        composeTestRule.onNodeWithText(string(R.string.fetch_on_demand)).performScrollTo().performClick()

        composeTestRule.waitUntil(5_000) {
            runBlocking { settingsRepository.fetchPolicy() } == FetchPolicy.ON_DEMAND
        }
    }

    @Test
    fun contactsAutocompleteRow_isShown() {
        // The contacts entry (#129) is wired into the real screen; it reflects the live permission
        // state, so we assert only that the row is present (state-specific rendering is covered by
        // ContactAutocompleteRowTest).
        setContent(settingsViewModel(SettingsRepository(context)))
        composeTestRule.onNodeWithText(string(R.string.settings_contacts_autocomplete))
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun enablingAppLockWithoutSecureDevice_showsRejectionSnackbar() {
        val settingsRepository = SettingsRepository(context)
        runBlocking { settingsRepository.setAppLock(false) } // known starting state: off
        setContent(settingsViewModel(settingsRepository))

        // App-lock lives under the collapsed "Advanced" section: expand it, then toggle the switch on.
        composeTestRule.onNodeWithText(string(R.string.settings_advanced)).performScrollTo().performClick()
        composeTestRule.onNodeWithText(string(R.string.settings_adv_app_lock)).performScrollTo().performClick()

        val message = string(R.string.app_lock_needs_device_lock)
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
