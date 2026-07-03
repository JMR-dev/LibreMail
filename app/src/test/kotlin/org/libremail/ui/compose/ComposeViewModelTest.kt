// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.compose

import androidx.lifecycle.SavedStateHandle
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.data.settings.AccountSettingsRepository
import org.libremail.data.settings.AppSettings
import org.libremail.data.settings.SettingsRepository
import org.libremail.data.settings.SignatureRepository
import org.libremail.domain.model.Account
import org.libremail.domain.model.AccountSettings
import org.libremail.domain.model.AuthType
import org.libremail.domain.model.Draft
import org.libremail.domain.model.MailSecurity
import org.libremail.domain.model.OutgoingAttachment
import org.libremail.domain.model.OutgoingMessage
import org.libremail.domain.model.ServerConfig
import org.libremail.domain.model.Signature
import org.libremail.domain.repository.AccountRepository
import org.libremail.domain.repository.MailRepository
import org.libremail.richtext.RichBaseStyle
import org.libremail.richtext.RichTextHtml
import org.libremail.ui.navigation.Routes
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ComposeViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(testDispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val alice = account("imap:a", "alice@example.org")
    private val bob = account("imap:b", "bob@example.org")

    private fun account(id: String, email: String) = Account(
        id = id,
        email = email,
        displayName = email,
        authType = AuthType.PASSWORD_IMAP,
        imap = ServerConfig("imap.example.org", 993, MailSecurity.SSL_TLS),
        smtp = ServerConfig("smtp.example.org", 465, MailSecurity.SSL_TLS),
    )

    private fun signature(accountId: String, html: String) =
        Signature(id = "$accountId:sig", accountId = accountId, name = "Signature", html = html, isDefault = true)

    private fun viewModel(
        accounts: List<Account> = listOf(alice),
        savedState: SavedStateHandle = SavedStateHandle(),
        signatures: Map<String, Signature> = emptyMap(),
        settings: Map<String, AccountSettings> = emptyMap(),
        mailRepository: MailRepository = mockk(relaxed = true),
        defaultAccountId: String? = null,
        lastFontCss: String? = null,
        lastFontSizePt: Int? = null,
        settingsRepository: SettingsRepository = fakeSettingsRepository(defaultAccountId, lastFontCss, lastFontSizePt),
    ): ComposeViewModel {
        val accountRepository = mockk<AccountRepository>()
        every { accountRepository.observeAccounts() } returns MutableStateFlow(accounts)
        val accountSettingsRepository = mockk<AccountSettingsRepository>()
        coEvery { accountSettingsRepository.get(any()) } answers {
            val id = firstArg<String>()
            settings[id] ?: AccountSettings(id)
        }
        val signatureRepository = mockk<SignatureRepository>()
        coEvery { signatureRepository.getDefault(any()) } answers { signatures[firstArg<String>()] }
        return ComposeViewModel(
            savedStateHandle = savedState,
            mailRepository = mailRepository,
            accountRepository = accountRepository,
            contactsRepository = mockk(relaxed = true),
            accountSettingsRepository = accountSettingsRepository,
            signatureRepository = signatureRepository,
            settingsRepository = settingsRepository,
        )
    }

    /**
     * A [SettingsRepository] mock carrying [AppSettings] built from the given fields, with
     * [SettingsRepository.setLastFont] stubbed so callers that don't care about it (most tests) don't need to.
     */
    private fun fakeSettingsRepository(
        defaultAccountId: String? = null,
        lastFontCss: String? = null,
        lastFontSizePt: Int? = null,
    ): SettingsRepository {
        val repository = mockk<SettingsRepository>()
        val appSettings = AppSettings(
            defaultAccountId = defaultAccountId,
            lastFontCss = lastFontCss,
            lastFontSizePt = lastFontSizePt,
        )
        every { repository.settings } returns flowOf(appSettings)
        coEvery { repository.setLastFont(any(), any()) } just Runs
        return repository
    }

    @Test
    fun `appends the sending account default signature to a new message`() = runTest(testDispatcher) {
        val vm = viewModel(signatures = mapOf("imap:a" to signature("imap:a", "Cheers, Alice")))

        assertEquals("\n\n-- \nCheers, Alice", vm.state.value.body)
        // A plain signature carries no formatting, so the message stays plaintext-only.
        assertNull(vm.state.value.bodyHtml)
    }

    @Test
    fun `a rich signature makes the new message carry an HTML body`() = runTest(testDispatcher) {
        val vm = viewModel(signatures = mapOf("imap:a" to signature("imap:a", "Cheers, <b>Alice</b>")))

        assertEquals("\n\n-- \nCheers, Alice", vm.state.value.body)
        val html = vm.state.value.bodyHtml
        assertTrue(html != null && html.contains("<b>Alice</b>"), "html=$html")
    }

    @Test
    fun `swaps the signature when the from account changes`() = runTest(testDispatcher) {
        val vm = viewModel(
            accounts = listOf(alice, bob),
            signatures = mapOf(
                "imap:a" to signature("imap:a", "Cheers, Alice"),
                "imap:b" to signature("imap:b", "Best, Bob"),
            ),
        )
        assertEquals("\n\n-- \nCheers, Alice", vm.state.value.body)

        vm.selectFrom("imap:b")

        assertEquals("\n\n-- \nBest, Bob", vm.state.value.body)
    }

    @Test
    fun `uses the persisted default account when no explicit from-account is given`() = runTest(testDispatcher) {
        val vm = viewModel(accounts = listOf(alice, bob), defaultAccountId = bob.id)

        assertEquals(bob.id, vm.state.value.fromAccountId)
    }

    @Test
    fun `falls back to the first account when the persisted default no longer exists`() = runTest(testDispatcher) {
        // Simulates a default that outlived its account (deletion, or a Backup restore onto a device
        // that never had it) — must fall back to the incidental first-alphabetically account, not crash.
        val vm = viewModel(accounts = listOf(alice, bob), defaultAccountId = "imap:deleted-account")

        assertEquals(alice.id, vm.state.value.fromAccountId)
    }

    @Test
    fun `falls back to the first account when no default is set`() = runTest(testDispatcher) {
        val vm = viewModel(accounts = listOf(alice, bob), defaultAccountId = null)

        assertEquals(alice.id, vm.state.value.fromAccountId)
    }

    @Test
    fun `an explicit from-account wins over the persisted default`() = runTest(testDispatcher) {
        val vm = viewModel(
            accounts = listOf(alice, bob),
            savedState = SavedStateHandle(mapOf(Routes.COMPOSE_ARG_FROM to alice.id)),
            defaultAccountId = bob.id,
        )

        assertEquals(alice.id, vm.state.value.fromAccountId)
    }

    @Test
    fun `does not append a signature when the account disabled it`() = runTest(testDispatcher) {
        val vm = viewModel(
            signatures = mapOf("imap:a" to signature("imap:a", "Cheers, Alice")),
            settings = mapOf("imap:a" to AccountSettings("imap:a", signatureEnabled = false)),
        )

        assertEquals("", vm.state.value.body)
    }

    @Test
    fun `seeds a brand-new composition with the remembered font`() = runTest(testDispatcher) {
        // No signature configured, so the body is empty going in — the seeded base style still wraps it.
        val vm = viewModel(lastFontCss = "Georgia, serif", lastFontSizePt = 14)

        val html = vm.state.value.bodyHtml
        assertTrue(html != null, "expected a seeded HTML body, was null")
        assertEquals(RichBaseStyle("Georgia, serif", 14), RichTextHtml.fromHtml(html).baseStyle)
    }

    @Test
    fun `seeds the remembered font onto a new message's signature`() = runTest(testDispatcher) {
        val vm = viewModel(
            signatures = mapOf("imap:a" to signature("imap:a", "Cheers, Alice")),
            lastFontCss = "Georgia, serif",
            lastFontSizePt = null,
        )

        val content = RichTextHtml.fromHtml(vm.state.value.bodyHtml!!)
        assertEquals(RichBaseStyle("Georgia, serif", null), content.baseStyle)
        // The signature text itself must still be present, just wrapped by the base style.
        assertTrue(content.text.contains("Cheers, Alice"), "text=${content.text}")
    }

    @Test
    fun `stays plaintext-only when composing new with no remembered font`() = runTest(testDispatcher) {
        val vm = viewModel(signatures = mapOf("imap:a" to signature("imap:a", "Cheers, Alice")))

        // No font preference stored: the plain signature must not gain an HTML body just from seeding.
        assertNull(vm.state.value.bodyHtml)
    }

    @Test
    fun `does not append a signature when resuming a draft`() = runTest(testDispatcher) {
        val mailRepository = mockk<MailRepository>(relaxed = true)
        coEvery { mailRepository.getDraft("d1") } returns Draft(
            id = "d1",
            accountId = "imap:a",
            to = "x@example.org",
            cc = "",
            subject = "Hi",
            body = "Draft body",
            updatedAt = 0L,
            bodyHtml = "<p>Draft <b>body</b></p>",
            attachments = emptyList(),
        )
        val vm = viewModel(
            savedState = SavedStateHandle(mapOf(Routes.COMPOSE_ARG_DRAFT to "d1")),
            signatures = mapOf("imap:a" to signature("imap:a", "Cheers, Alice")),
            mailRepository = mailRepository,
        )

        assertEquals("Draft body", vm.state.value.body)
        // The draft's HTML body is restored so it round-trips back out on send.
        assertEquals("<p>Draft <b>body</b></p>", vm.state.value.bodyHtml)
    }

    @Test
    fun `does not seed the remembered font onto an existing draft`() = runTest(testDispatcher) {
        val mailRepository = mockk<MailRepository>(relaxed = true)
        coEvery { mailRepository.getDraft("d1") } returns Draft(
            id = "d1",
            accountId = "imap:a",
            to = "x@example.org",
            cc = "",
            subject = "Hi",
            body = "Draft body",
            updatedAt = 0L,
            bodyHtml = "<p>Draft <b>body</b></p>",
            attachments = emptyList(),
        )
        val vm = viewModel(
            savedState = SavedStateHandle(mapOf(Routes.COMPOSE_ARG_DRAFT to "d1")),
            mailRepository = mailRepository,
            lastFontCss = "Georgia, serif",
            lastFontSizePt = 14,
        )

        assertEquals("Draft body", vm.state.value.body)
        // Only brand-new compositions seed the remembered font; a resumed draft's HTML is untouched.
        assertEquals("<p>Draft <b>body</b></p>", vm.state.value.bodyHtml)
    }

    @Test
    fun `resuming a draft restores the bcc recipients`() = runTest(testDispatcher) {
        val mailRepository = mockk<MailRepository>(relaxed = true)
        coEvery { mailRepository.getDraft("d1") } returns Draft(
            id = "d1",
            accountId = "imap:a",
            to = "x@example.org",
            cc = "",
            bcc = "hidden@example.org",
            subject = "Hi",
            body = "Draft body",
            updatedAt = 0L,
        )
        val vm = viewModel(
            savedState = SavedStateHandle(mapOf(Routes.COMPOSE_ARG_DRAFT to "d1")),
            mailRepository = mailRepository,
        )

        assertEquals("hidden@example.org", vm.state.value.bcc)
    }

    @Test
    fun `send carries the HTML body through to the outgoing message`() = runTest(testDispatcher) {
        val mailRepository = mockk<MailRepository>(relaxed = true)
        val sent = slot<OutgoingMessage>()
        coEvery { mailRepository.sendMessage(capture(sent)) } returns Result.success(Unit)
        val vm = viewModel(mailRepository = mailRepository)

        vm.onToChange("bob@example.org")
        vm.onBodyChange("Hello", "<p>Hello <i>world</i></p>")
        vm.send()

        assertEquals("<p>Hello <i>world</i></p>", sent.captured.bodyHtml)
        assertEquals("Hello", sent.captured.body)
    }

    @Test
    fun `persists the message-wide base style font on send`() = runTest(testDispatcher) {
        val mailRepository = mockk<MailRepository>(relaxed = true)
        coEvery { mailRepository.sendMessage(any()) } returns Result.success(Unit)
        val settingsRepository = fakeSettingsRepository()
        val vm = viewModel(mailRepository = mailRepository, settingsRepository = settingsRepository)

        vm.onToChange("bob@example.org")
        vm.onBodyChange("Hello", "<div style=\"font-family:Georgia, serif;font-size:14pt\"><p>Hello</p></div>")
        vm.send()

        coVerify { settingsRepository.setLastFont("Georgia, serif", 14) }
    }

    @Test
    fun `persists the last font span when the message has no base style`() = runTest(testDispatcher) {
        val mailRepository = mockk<MailRepository>(relaxed = true)
        coEvery { mailRepository.sendMessage(any()) } returns Result.success(Unit)
        val settingsRepository = fakeSettingsRepository()
        val vm = viewModel(mailRepository = mailRepository, settingsRepository = settingsRepository)

        vm.onToChange("bob@example.org")
        vm.onBodyChange(
            "Hello there",
            "<p><span style=\"font-family:Arial\">Hello</span> " +
                "<span style=\"font-size:18pt\">there</span></p>",
        )
        vm.send()

        // Each axis independently takes the last matching span: family from "Hello", size from "there".
        coVerify { settingsRepository.setLastFont("Arial", 18) }
    }

    @Test
    fun `does not persist anything when the sent message has no font styling`() = runTest(testDispatcher) {
        val mailRepository = mockk<MailRepository>(relaxed = true)
        coEvery { mailRepository.sendMessage(any()) } returns Result.success(Unit)
        val settingsRepository = fakeSettingsRepository()
        val vm = viewModel(mailRepository = mailRepository, settingsRepository = settingsRepository)

        vm.onToChange("bob@example.org")
        vm.onBodyChange("Hello world", "<p>Hello <b>world</b></p>")
        vm.send()

        coVerify(exactly = 0) { settingsRepository.setLastFont(any(), any()) }
    }

    @Test
    fun `does not persist anything for a plaintext-only send`() = runTest(testDispatcher) {
        val mailRepository = mockk<MailRepository>(relaxed = true)
        coEvery { mailRepository.sendMessage(any()) } returns Result.success(Unit)
        val settingsRepository = fakeSettingsRepository()
        val vm = viewModel(mailRepository = mailRepository, settingsRepository = settingsRepository)

        vm.onToChange("bob@example.org")
        vm.onBodyChange("Hello world", null)
        vm.send()

        coVerify(exactly = 0) { settingsRepository.setLastFont(any(), any()) }
    }

    @Test
    fun `prefills the form from mailto navigation arguments`() = runTest(testDispatcher) {
        val vm = viewModel(
            savedState = SavedStateHandle(
                mapOf(
                    Routes.COMPOSE_ARG_TO to "a@example.org, b@example.org",
                    Routes.COMPOSE_ARG_CC to "c@example.org",
                    Routes.COMPOSE_ARG_BCC to "d@example.org",
                    Routes.COMPOSE_ARG_SUBJECT to "Lunch?",
                    Routes.COMPOSE_ARG_BODY to "Are you free",
                ),
            ),
        )

        val state = vm.state.value
        assertEquals("a@example.org, b@example.org", state.to)
        assertEquals("c@example.org", state.cc)
        assertEquals("d@example.org", state.bcc)
        assertEquals("Lunch?", state.subject)
        // No signature configured, so the mailto body is used verbatim.
        assertEquals("Are you free", state.body)
    }

    @Test
    fun `send carries the bcc recipients to the repository`() = runTest(testDispatcher) {
        val mailRepository = mockk<MailRepository>(relaxed = true)
        coEvery { mailRepository.sendMessage(any()) } returns Result.success(Unit)
        val vm = viewModel(
            savedState = SavedStateHandle(
                mapOf(
                    Routes.COMPOSE_ARG_TO to "a@example.org",
                    Routes.COMPOSE_ARG_BCC to "secret@example.org",
                ),
            ),
            mailRepository = mailRepository,
        )

        vm.send()

        val sent = slot<OutgoingMessage>()
        coVerify { mailRepository.sendMessage(capture(sent)) }
        assertEquals("secret@example.org", sent.captured.bcc)
    }

    @Test
    fun `asks about attachments when the body mentions one but none is attached`() = runTest(testDispatcher) {
        val mailRepository = mockk<MailRepository>(relaxed = true)
        val vm = viewModel(mailRepository = mailRepository)

        vm.onToChange("bob@example.org")
        vm.onBodyChange("I attached the report.", null)
        vm.send()

        assertTrue(vm.state.value.showAttachmentPrompt)
        coVerify(exactly = 0) { mailRepository.sendMessage(any()) }
    }

    @Test
    fun `asks about attachments when only the subject mentions one`() = runTest(testDispatcher) {
        val mailRepository = mockk<MailRepository>(relaxed = true)
        val vm = viewModel(mailRepository = mailRepository)

        vm.onToChange("bob@example.org")
        vm.onSubjectChange("Contract attachment")
        vm.send()

        assertTrue(vm.state.value.showAttachmentPrompt)
        coVerify(exactly = 0) { mailRepository.sendMessage(any()) }
    }

    @Test
    fun `matches attachment variants but not lookalike words`() = runTest(testDispatcher) {
        val mailRepository = mockk<MailRepository>(relaxed = true)
        coEvery { mailRepository.sendMessage(any()) } returns Result.success(Unit)

        listOf("Attached is the file", "attaching it now", "see the ATTACHMENTS", "can you attach it").forEach {
            val vm = viewModel(mailRepository = mailRepository)
            vm.onToChange("bob@example.org")
            vm.onBodyChange(it, null)
            vm.send()
            assertTrue(vm.state.value.showAttachmentPrompt, "should prompt for: $it")
        }

        listOf("planning an attack", "the base is attachable", "no keyword here").forEach {
            val vm = viewModel(mailRepository = mailRepository)
            vm.onToChange("bob@example.org")
            vm.onBodyChange(it, null)
            vm.send()
            assertFalse(vm.state.value.showAttachmentPrompt, "should not prompt for: $it")
        }
    }

    @Test
    fun `sends without asking when an attachment is present`() = runTest(testDispatcher) {
        val mailRepository = mockk<MailRepository>(relaxed = true)
        coEvery { mailRepository.sendMessage(any()) } returns Result.success(Unit)
        val vm = viewModel(mailRepository = mailRepository)

        vm.onToChange("bob@example.org")
        vm.onBodyChange("The report is attached.", null)
        vm.addAttachments(listOf(OutgoingAttachment("content://docs/report.pdf", "report.pdf")))
        vm.send()

        assertFalse(vm.state.value.showAttachmentPrompt)
        coVerify(exactly = 1) { mailRepository.sendMessage(any()) }
    }

    @Test
    fun `sendAnyway sends the message the prompt held back`() = runTest(testDispatcher) {
        val mailRepository = mockk<MailRepository>(relaxed = true)
        coEvery { mailRepository.sendMessage(any()) } returns Result.success(Unit)
        val vm = viewModel(mailRepository = mailRepository)

        vm.onToChange("bob@example.org")
        vm.onBodyChange("I attached the report.", null)
        vm.send()
        assertTrue(vm.state.value.showAttachmentPrompt)

        vm.sendAnyway()

        assertFalse(vm.state.value.showAttachmentPrompt)
        coVerify(exactly = 1) { mailRepository.sendMessage(any()) }
    }

    @Test
    fun `attachInstead returns to composing and highlights the attach button`() = runTest(testDispatcher) {
        val mailRepository = mockk<MailRepository>(relaxed = true)
        val vm = viewModel(mailRepository = mailRepository)

        vm.onToChange("bob@example.org")
        vm.onBodyChange("Attachment coming.", null)
        vm.send()

        vm.attachInstead()

        assertFalse(vm.state.value.showAttachmentPrompt)
        assertTrue(vm.state.value.highlightAttach)
        coVerify(exactly = 0) { mailRepository.sendMessage(any()) }

        vm.consumeAttachHighlight()
        assertFalse(vm.state.value.highlightAttach)
    }

    @Test
    fun `dismissing the prompt cancels the send without highlighting`() = runTest(testDispatcher) {
        val mailRepository = mockk<MailRepository>(relaxed = true)
        val vm = viewModel(mailRepository = mailRepository)

        vm.onToChange("bob@example.org")
        vm.onBodyChange("See attached.", null)
        vm.send()

        vm.dismissAttachmentPrompt()

        assertFalse(vm.state.value.showAttachmentPrompt)
        assertFalse(vm.state.value.highlightAttach)
        coVerify(exactly = 0) { mailRepository.sendMessage(any()) }
    }
}
