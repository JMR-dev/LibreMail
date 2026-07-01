// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.R
import org.libremail.data.security.AppLockAvailability
import org.libremail.data.security.AppLockManager
import org.libremail.data.security.DatabaseKeyCipher
import org.libremail.data.security.DatabaseKeyStore
import org.libremail.data.security.KeystoreCrypto
import org.libremail.data.security.PassphraseSession
import org.libremail.data.settings.FetchPolicy
import org.libremail.data.settings.SettingsRepository
import org.libremail.ui.FakeAccountRepository
import org.libremail.ui.theme.LibreMailTheme

/**
 * End-to-end test for the top-level message-downloading setting: tapping a policy must round-trip
 * through the real [SettingsRepository] (DataStore), proving the UI → ViewModel → persistence wiring.
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun string(resId: Int) = composeTestRule.activity.getString(resId)

    @Test
    fun selectingFetchPolicy_persistsThroughTheRepository() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val settingsRepository = SettingsRepository(context)
        runBlocking { settingsRepository.setFetchPolicy(FetchPolicy.ALWAYS) } // known starting state
        val appLockManager = object : AppLockManager {
            override fun isDeviceSecure() = false
            override fun availability() = AppLockAvailability.NONE_ENROLLED
        }
        val keyStore = DatabaseKeyStore(context, KeystoreCrypto(), DatabaseKeyCipher(), PassphraseSession())
        val viewModel = SettingsViewModel(FakeAccountRepository(), settingsRepository, appLockManager, keyStore)

        composeTestRule.setContent {
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                SettingsScreen(onAddAccount = {}, onOpenAccount = {}, onSelectTab = {}, viewModel = viewModel)
            }
        }

        composeTestRule.onNodeWithText(string(R.string.fetch_on_demand)).performScrollTo().performClick()

        composeTestRule.waitUntil(5_000) {
            runBlocking { settingsRepository.fetchPolicy() } == FetchPolicy.ON_DEMAND
        }
    }
}
