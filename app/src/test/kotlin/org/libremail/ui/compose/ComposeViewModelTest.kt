// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.compose

import androidx.lifecycle.SavedStateHandle
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertEquals
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
import org.libremail.domain.model.Account
import org.libremail.domain.model.AccountSettings
import org.libremail.domain.model.AuthType
import org.libremail.domain.model.Draft
import org.libremail.domain.model.MailSecurity
import org.libremail.domain.model.ServerConfig
import org.libremail.domain.repository.AccountRepository
import org.libremail.domain.repository.MailRepository
import org.libremail.ui.navigation.Routes

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

    private fun viewModel(
        accounts: List<Account> = listOf(alice),
        savedState: SavedStateHandle = SavedStateHandle(),
        signatures: Map<String, AccountSettings> = emptyMap(),
        mailRepository: MailRepository = mockk(relaxed = true),
    ): ComposeViewModel {
        val accountRepository = mockk<AccountRepository>()
        every { accountRepository.observeAccounts() } returns MutableStateFlow(accounts)
        val accountSettingsRepository = mockk<AccountSettingsRepository>()
        coEvery { accountSettingsRepository.get(any()) } answers {
            val id = firstArg<String>()
            signatures[id] ?: AccountSettings(id)
        }
        return ComposeViewModel(
            savedStateHandle = savedState,
            mailRepository = mailRepository,
            accountRepository = accountRepository,
            contactsRepository = mockk(relaxed = true),
            accountSettingsRepository = accountSettingsRepository,
        )
    }

    @Test
    fun `appends the sending account signature to a new message`() = runTest(testDispatcher) {
        val vm = viewModel(signatures = mapOf("imap:a" to AccountSettings("imap:a", signature = "Cheers, Alice")))

        assertEquals("\n\n-- \nCheers, Alice", vm.state.value.body)
    }

    @Test
    fun `swaps the signature when the from account changes`() = runTest(testDispatcher) {
        val vm = viewModel(
            accounts = listOf(alice, bob),
            signatures = mapOf(
                "imap:a" to AccountSettings("imap:a", signature = "Cheers, Alice"),
                "imap:b" to AccountSettings("imap:b", signature = "Best, Bob"),
            ),
        )
        assertEquals("\n\n-- \nCheers, Alice", vm.state.value.body)

        vm.selectFrom("imap:b")

        assertEquals("\n\n-- \nBest, Bob", vm.state.value.body)
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
            attachments = emptyList(),
        )
        val vm = viewModel(
            savedState = SavedStateHandle(mapOf(Routes.COMPOSE_ARG_DRAFT to "d1")),
            signatures = mapOf("imap:a" to AccountSettings("imap:a", signature = "Cheers, Alice")),
            mailRepository = mailRepository,
        )

        assertEquals("Draft body", vm.state.value.body)
    }
}
