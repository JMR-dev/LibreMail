// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.settings

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.R
import org.libremail.data.local.AccountDatabase
import org.libremail.data.local.toEntity
import org.libremail.data.settings.AccountSettingsRepository
import org.libremail.data.settings.SettingsRepository
import org.libremail.data.settings.SignatureRepository
import org.libremail.data.sync.SyncScheduler
import org.libremail.domain.model.Account
import org.libremail.domain.model.AuthType
import org.libremail.domain.model.MailSecurity
import org.libremail.domain.model.ServerConfig
import org.libremail.ui.FakeAccountRepository
import org.libremail.ui.navigation.Routes
import org.libremail.ui.theme.LibreMailTheme
import javax.inject.Provider

/**
 * End-to-end test for the per-account settings screen: editing the signature and toggling the
 * per-account notification switch must round-trip through the real Room-backed repository.
 */
@RunWith(AndroidJUnit4::class)
class AccountSettingsScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val account = Account(
        id = "imap:me@example.com",
        email = "me@example.com",
        displayName = "Me",
        authType = AuthType.PASSWORD_IMAP,
        imap = ServerConfig("imap.example.com", 993, MailSecurity.SSL_TLS),
        smtp = ServerConfig("smtp.example.com", 465, MailSecurity.SSL_TLS),
    )

    private fun string(resId: Int) = composeTestRule.activity.getString(resId)

    private var manageSignaturesClicked = false

    private lateinit var db: AccountDatabase

    // Holds the real AccountSettingsViewModel built by hand in setContent() below, so tearDown() can
    // clear() it (triggering ViewModel.onCleared()) before closing the DB.
    private val viewModelStore = ViewModelStore()

    @After
    fun tearDown() {
        // Clear the store (→ ViewModel.onCleared() → cancels viewModelScope) BEFORE closing the DB.
        // The ViewModel's `settings`/`signatureCount`/`defaultSignatureName`/`account` Room
        // InvalidationTracker Flows are kept alive by stateIn(WhileSubscribed(5_000)): without this,
        // a collector can still be live up to 5s after the UI detaches, so a re-query lands on the
        // just-closed in-memory DB and throws SQLITE_MISUSE ("connection is closed") — an intermittent
        // teardown race, not a real bug. (Previously worked around by never closing the DB at all.)
        viewModelStore.clear()
        db.close()
    }

    private fun setContent(): AccountSettingsRepository {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AccountDatabase::class.java).build()
        val repository = AccountSettingsRepository(db.accountSettingsDao())
        runBlocking {
            db.accountDao().upsert(account.toEntity()) // FK parent for the account_settings row
            repository.ensureDefaults(account.id)
        }
        val viewModel = AccountSettingsViewModel(
            savedStateHandle = SavedStateHandle(mapOf(Routes.ACCOUNT_SETTINGS_ARG_ID to account.id)),
            accountRepository = FakeAccountRepository(accounts = listOf(account)),
            accountSettingsRepository = repository,
            signatureRepository = SignatureRepository(db.signatureDao()),
            syncScheduler = SyncScheduler(Provider { WorkManager.getInstance(context) }),
            settingsRepository = SettingsRepository(context),
        )
        viewModelStore.put("account-settings", viewModel)
        composeTestRule.setContent {
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                AccountSettingsScreen(
                    onBack = {},
                    onManageSignatures = { manageSignaturesClicked = true },
                    viewModel = viewModel,
                )
            }
        }
        return repository
    }

    @Test
    fun manageSignatures_opensTheSignaturesScreen() {
        setContent()

        composeTestRule.onNodeWithText(string(R.string.settings_signatures_manage)).performClick()

        composeTestRule.waitUntil(5_000) { manageSignaturesClicked }
    }

    @Test
    fun togglingNotifications_persistsThroughTheRepository() {
        val repository = setContent() // ensureDefaults starts notifications enabled

        composeTestRule.onNodeWithText(string(R.string.settings_account_new_mail)).performClick()

        composeTestRule.waitUntil(5_000) {
            runBlocking { !repository.get(account.id).notificationsEnabled }
        }
    }
}
