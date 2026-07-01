// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.compose

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.R
import org.libremail.contacts.ContactsRepository
import org.libremail.data.local.LibreMailDatabase
import org.libremail.data.settings.AccountSettingsRepository
import org.libremail.data.settings.SignatureRepository
import org.libremail.domain.model.Account
import org.libremail.domain.model.AuthType
import org.libremail.domain.model.MailSecurity
import org.libremail.domain.model.ServerConfig
import org.libremail.ui.FakeAccountRepository
import org.libremail.ui.FakeMailRepository
import org.libremail.ui.theme.LibreMailTheme

/**
 * End-to-end UI test for composing and sending a message. Drives the real [ComposeScreen] and a
 * real [ComposeViewModel] backed by in-memory fakes plus the real (empty) device contacts provider.
 */
@RunWith(AndroidJUnit4::class)
class ComposeScreenTest {

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

    private var db: LibreMailDatabase? = null

    private fun string(resId: Int) = composeTestRule.activity.getString(resId)

    @After
    fun closeDb() {
        db?.close()
    }

    @Before
    fun grantContactsPermission() {
        // ComposeScreen requests READ_CONTACTS on first composition; pre-grant it (before the test
        // calls setContent) so no system permission dialog appears to block the headless run.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.READ_CONTACTS,
        )
    }

    // Build the view model once and capture it, so recomposition doesn't recreate it.
    private fun setContent(mailRepository: FakeMailRepository = FakeMailRepository(), onBack: () -> Unit = {}) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val database = Room.inMemoryDatabaseBuilder(context, LibreMailDatabase::class.java).build().also { db = it }
        val viewModel = ComposeViewModel(
            savedStateHandle = SavedStateHandle(),
            mailRepository = mailRepository,
            accountRepository = FakeAccountRepository(accounts = listOf(account)),
            contactsRepository = ContactsRepository(context),
            accountSettingsRepository = AccountSettingsRepository(database.accountSettingsDao()),
            signatureRepository = SignatureRepository(database.signatureDao()),
        )
        composeTestRule.setContent {
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                ComposeScreen(onBack = onBack, viewModel = viewModel)
            }
        }
    }

    @Test
    fun sendButton_isDisabledUntilRecipientEntered() {
        setContent()
        composeTestRule.onNodeWithContentDescription(string(R.string.action_send)).assertIsNotEnabled()
        composeTestRule.onNodeWithText(string(R.string.compose_to)).performTextInput("you@example.com")
        composeTestRule.onNodeWithContentDescription(string(R.string.action_send)).assertIsEnabled()
    }

    @Test
    fun send_withRecipient_sendsMessageAndClosesScreen() {
        val mailRepository = FakeMailRepository()
        var closed = false
        setContent(mailRepository) { closed = true }

        composeTestRule.onNodeWithText(string(R.string.compose_to)).performTextInput("you@example.com")
        composeTestRule.onNodeWithText(string(R.string.compose_subject)).performTextInput("Hello")
        composeTestRule.onNodeWithText(string(R.string.compose_body)).performTextInput("Body text")
        composeTestRule.onNodeWithContentDescription(string(R.string.action_send)).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) { mailRepository.sentMessages.isNotEmpty() }
        val sent = mailRepository.sentMessages.single()
        assertEquals("you@example.com", sent.to)
        assertEquals("Hello", sent.subject)
        assertEquals(account.id, sent.accountId)

        composeTestRule.waitUntil(timeoutMillis = 5_000) { closed }
    }
}
