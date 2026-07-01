// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.compose

import androidx.lifecycle.SavedStateHandle
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.data.settings.AccountSettingsRepository
import org.libremail.data.settings.SignatureRepository
import org.libremail.domain.model.Account
import org.libremail.domain.model.AccountSettings
import org.libremail.domain.model.AuthType
import org.libremail.domain.model.Draft
import org.libremail.domain.model.MailSecurity
import org.libremail.domain.model.OutgoingMessage
import org.libremail.domain.model.ServerConfig
import org.libremail.domain.model.Signature
import org.libremail.domain.repository.AccountRepository
import org.libremail.domain.repository.MailRepository
import org.libremail.ui.navigation.Routes
import kotlin.test.assertEquals
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
        )
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
    fun `does not append a signature when the account disabled it`() = runTest(testDispatcher) {
        val vm = viewModel(
            signatures = mapOf("imap:a" to signature("imap:a", "Cheers, Alice")),
            settings = mapOf("imap:a" to AccountSettings("imap:a", signatureEnabled = false)),
        )

        assertEquals("", vm.state.value.body)
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
}
