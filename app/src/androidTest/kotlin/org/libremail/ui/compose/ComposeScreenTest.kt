// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.compose

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.R
import org.libremail.contacts.ContactsRepository
import org.libremail.data.local.AccountDatabase
import org.libremail.data.settings.AccountSettingsRepository
import org.libremail.data.settings.SettingsRepository
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

    private var db: AccountDatabase? = null

    // Holds the real ComposeViewModel built by hand in setContent() below, so closeDb() can clear()
    // it (triggering ViewModel.onCleared()) before closing the DB.
    private val viewModelStore = ViewModelStore()

    private fun string(resId: Int) = composeTestRule.activity.getString(resId)

    @After
    fun closeDb() {
        // Clear the store (→ ViewModel.onCleared() → cancels viewModelScope) BEFORE closing the DB.
        // ComposeViewModel's init block launches a viewModelScope coroutine that reads the real
        // accountSettings/signature Room repositories (applySignature()); without this, that read can
        // still be in flight when the DB closes, racing a SQLITE_MISUSE ("connection is closed") —
        // the same class of teardown race fixed in SignaturesScreenTest/AccountSettingsScreenTest.
        viewModelStore.clear()
        db?.close()
    }

    @Before
    fun grantContactsPermission() {
        // ComposeScreen no longer requests READ_CONTACTS (the request moved to onboarding/#127); it
        // only reads the current grant on resume. Pre-grant it (before setContent) so contactsAllowed
        // resolves true and the autocomplete path stays exercised — no system dialog is ever shown.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.READ_CONTACTS,
        )
    }

    // Build the view model once and capture it, so recomposition doesn't recreate it.
    private fun setContent(mailRepository: FakeMailRepository = FakeMailRepository(), onBack: () -> Unit = {}) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val database = Room.inMemoryDatabaseBuilder(context, AccountDatabase::class.java).build().also { db = it }
        val viewModel = ComposeViewModel(
            savedStateHandle = SavedStateHandle(),
            mailRepository = mailRepository,
            accountRepository = FakeAccountRepository(accounts = listOf(account)),
            contactsRepository = ContactsRepository(context),
            accountSettingsRepository = AccountSettingsRepository(database.accountSettingsDao()),
            signatureRepository = SignatureRepository(database.signatureDao()),
            settingsRepository = SettingsRepository(context),
        )
        viewModelStore.put("compose", viewModel)
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

    @Test
    fun send_whenBodyMentionsAttachmentWithoutOne_promptsBeforeSending() {
        val mailRepository = FakeMailRepository()
        setContent(mailRepository)

        composeTestRule.onNodeWithText(string(R.string.compose_to)).performTextInput("you@example.com")
        composeTestRule.onNodeWithText(string(R.string.compose_body)).performTextInput("I attached the report")
        composeTestRule.onNodeWithContentDescription(string(R.string.action_send)).performClick()

        // "Yes" returns to composing: the dialog closes and nothing is sent.
        composeTestRule.onNodeWithText(string(R.string.confirm_attachment_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.action_yes)).performClick()
        composeTestRule.onNodeWithText(string(R.string.confirm_attachment_title)).assertDoesNotExist()
        assertTrue(mailRepository.sentMessages.isEmpty())

        // Sending again and answering "No" delivers the message as-is.
        composeTestRule.onNodeWithContentDescription(string(R.string.action_send)).performClick()
        composeTestRule.onNodeWithText(string(R.string.action_no)).performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) { mailRepository.sentMessages.isNotEmpty() }
        assertEquals("I attached the report", mailRepository.sentMessages.single().body)
    }

    @Test
    fun ccAndBcc_startCollapsed_expandViaLinksAndCarryThroughSend() {
        val mailRepository = FakeMailRepository()
        setContent(mailRepository)

        // Collapsed: the Cc/Bcc labels exist only as links, not as editable fields.
        editableField(R.string.compose_cc).assertDoesNotExist()
        editableField(R.string.compose_bcc).assertDoesNotExist()

        composeTestRule.onNodeWithText(string(R.string.compose_cc)).performClick()
        editableField(R.string.compose_cc).performTextInput("cc@example.com")
        composeTestRule.onNodeWithText(string(R.string.compose_bcc)).performClick()
        editableField(R.string.compose_bcc).performTextInput("bcc@example.com")

        composeTestRule.onNodeWithText(string(R.string.compose_to)).performTextInput("you@example.com")
        composeTestRule.onNodeWithContentDescription(string(R.string.action_send)).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) { mailRepository.sentMessages.isNotEmpty() }
        val sent = mailRepository.sentMessages.single()
        assertEquals("cc@example.com", sent.cc)
        assertEquals("bcc@example.com", sent.bcc)
    }

    @Test
    fun formattingToolbar_bulletButtonMarksTheLineAndSendsItAsHtml() {
        val mailRepository = FakeMailRepository()
        setContent(mailRepository)

        composeTestRule.onNodeWithText(string(R.string.compose_to)).performTextInput("you@example.com")
        composeTestRule.onNodeWithText(string(R.string.compose_body)).performTextInput("Buy milk")
        // The bullet-list button is deliberately chosen over the inline styles (bold/italic): block
        // markers apply to the caret's whole line, so the end-of-text caret that performTextInput
        // leaves is enough - no on-device range selection (which is unreliable in instrumented tests)
        // is needed to prove that a toolbar tap flows real formatting into the sent message's HTML.
        // "•" is the bullet button's own (untranslated) glyph label - see FormattingToolbar.
        composeTestRule.onNodeWithText("•").performClick()
        composeTestRule.onNodeWithContentDescription(string(R.string.action_send)).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) { mailRepository.sentMessages.isNotEmpty() }
        val sent = mailRepository.sentMessages.single()
        // Plaintext keeps the readable "• " marker; the HTML part carries the real <ul>/<li> structure.
        assertEquals("• Buy milk", sent.body)
        assertTrue(
            "expected bullet-list html, got ${sent.bodyHtml}",
            sent.bodyHtml?.contains("<ul><li>Buy milk</li></ul>") == true,
        )
    }

    @Test
    fun formattingToolbar_buttonsCarryOnClickLabelsForAccessibility() {
        setContent()

        // Every toolbar button (see FormattingToolbar in RichTextEditor.kt) is a plain clickable Box with
        // a bare glyph Text as its only visible content, so TalkBack relies entirely on the click action's
        // label (there is no separate contentDescription) to announce what the button does.
        val buttons = listOf(
            "B" to R.string.format_bold,
            "I" to R.string.format_italic,
            "U" to R.string.format_underline,
            "•" to R.string.format_bullet_list,
            "1." to R.string.format_numbered_list,
            "❝" to R.string.format_quote,
            "🔗" to R.string.format_link,
        )
        buttons.forEach { (glyph, descriptionRes) ->
            val config = composeTestRule.onNodeWithText(glyph).fetchSemanticsNode().config
            val clickLabel = if (config.contains(SemanticsActions.OnClick)) {
                config[SemanticsActions.OnClick].label
            } else {
                null
            }
            val message = "toolbar button \"$glyph\" is missing its accessibility label"
            assertEquals(message, string(descriptionRes), clickLabel)
        }
    }

    /** Matches the editable field labelled [labelRes] but not the collapsed Cc/Bcc link buttons. */
    private fun editableField(labelRes: Int) = composeTestRule.onNode(hasText(string(labelRes)) and hasSetTextAction())
}
