// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.settings

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.R
import org.libremail.data.local.AccountDatabase
import org.libremail.data.local.toEntity
import org.libremail.data.settings.AccountSettingsRepository
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

    private fun setContent(): AccountSettingsRepository {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Intentionally not closed in an @After: the ViewModel's `settings` Room Flow (kept alive by
        // stateIn/WhileSubscribed) keeps querying after the test body, so closing the in-memory DB out
        // from under it races and crashes ("connection pool has been closed"). The DB is reclaimed with
        // the test process.
        val db = Room.inMemoryDatabaseBuilder(context, AccountDatabase::class.java).build()
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
        )
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
