// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.mailbox

import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.data.sync.MailSyncer
import org.libremail.domain.model.Account
import org.libremail.domain.model.AuthType
import org.libremail.domain.model.Folder
import org.libremail.domain.model.FolderRole
import org.libremail.domain.model.MailSecurity
import org.libremail.domain.model.Message
import org.libremail.domain.model.ServerConfig
import org.libremail.domain.repository.AccountRepository
import org.libremail.domain.repository.MailRepository

@OptIn(ExperimentalCoroutinesApi::class)
class MailboxViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(testDispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val alice = account("imap:a", "alice@example.org")
    private val bob = account("imap:b", "bob@example.org")

    @Test
    fun `default view shows only inbox messages across all accounts`() = runTest(testDispatcher) {
        val vm = createViewModel(
            accounts = listOf(alice, bob),
            messages = listOf(
                msg("imap:a:INBOX:1", "imap:a", "INBOX"),
                msg("imap:a:Archive:1", "imap:a", "Archive"),
                msg("imap:b:INBOX:1", "imap:b", "INBOX"),
            ),
        )
        backgroundScope.launch { vm.messages.collect {} }

        assertEquals("INBOX", vm.selectedFolder.value)
        assertNull(vm.selectedAccountId.value)
        assertEquals(setOf("imap:a:INBOX:1", "imap:b:INBOX:1"), vm.messages.value.map { it.id }.toSet())
    }

    @Test
    fun `selecting a folder scopes the view to that account and folder and syncs it`() = runTest(testDispatcher) {
        val syncer = mockk<MailSyncer>(relaxed = true)
        val vm = createViewModel(
            accounts = listOf(alice),
            messages = listOf(
                msg("imap:a:INBOX:1", "imap:a", "INBOX"),
                msg("imap:a:Archive:1", "imap:a", "Archive"),
            ),
            syncer = syncer,
        )
        backgroundScope.launch { vm.messages.collect {} }

        vm.selectFolder("imap:a", "Archive")

        assertEquals("Archive", vm.selectedFolder.value)
        assertEquals("imap:a", vm.selectedAccountId.value)
        assertEquals(listOf("imap:a:Archive:1"), vm.messages.value.map { it.id })
        coVerify { syncer.syncFolder("imap:a", "Archive") }
    }

    @Test
    fun `folders expose the drawer account's folders in order`() = runTest(testDispatcher) {
        val vm = createViewModel(
            accounts = listOf(alice),
            messages = emptyList(),
            folders = mapOf(
                "imap:a" to listOf(
                    folder("imap:a", "INBOX", FolderRole.INBOX),
                    folder("imap:a", "[Gmail]/Sent Mail", FolderRole.SENT),
                ),
            ),
        )
        backgroundScope.launch { vm.folders.collect {} }

        assertEquals(listOf("INBOX", "[Gmail]/Sent Mail"), vm.folders.value.map { it.fullName })
    }

    @Test
    fun `folders always include an inbox entry even before a folder refresh`() = runTest(testDispatcher) {
        val vm = createViewModel(
            accounts = listOf(alice),
            messages = emptyList(),
            folders = mapOf("imap:a" to listOf(folder("imap:a", "Receipts", FolderRole.NORMAL))),
        )
        backgroundScope.launch { vm.folders.collect {} }

        assertTrue(vm.folders.value.any { it.fullName == "INBOX" }, "drawer must always offer an inbox")
    }

    @Test
    fun `selectUnifiedInbox returns to the unified inbox`() = runTest(testDispatcher) {
        val vm = createViewModel(
            accounts = listOf(alice, bob),
            messages = listOf(msg("imap:a:Archive:1", "imap:a", "Archive")),
        )
        backgroundScope.launch { vm.messages.collect {} }
        vm.selectFolder("imap:a", "Archive")
        assertEquals("Archive", vm.selectedFolder.value)

        vm.selectUnifiedInbox()

        assertEquals("INBOX", vm.selectedFolder.value)
        assertNull(vm.selectedAccountId.value)
    }

    @Test
    fun `setDrawerAccount switches which account's folders the drawer shows`() = runTest(testDispatcher) {
        val vm = createViewModel(
            accounts = listOf(alice, bob),
            messages = emptyList(),
            folders = mapOf(
                "imap:a" to listOf(folder("imap:a", "INBOX", FolderRole.INBOX), folder("imap:a", "Archive", FolderRole.ARCHIVE)),
                "imap:b" to listOf(folder("imap:b", "INBOX", FolderRole.INBOX), folder("imap:b", "Work", FolderRole.NORMAL)),
            ),
        )
        backgroundScope.launch { vm.folders.collect {} }
        // The drawer defaults to the first account.
        assertTrue(vm.folders.value.any { it.fullName == "Archive" })

        vm.setDrawerAccount("imap:b")

        assertTrue(vm.folders.value.any { it.fullName == "Work" }, "drawer should now show bob's folders")
        assertTrue(vm.folders.value.none { it.fullName == "Archive" }, "alice's folders should no longer show")
    }

    private fun createViewModel(
        accounts: List<Account>,
        messages: List<Message>,
        folders: Map<String, List<Folder>> = emptyMap(),
        syncer: MailSyncer = mockk(relaxed = true),
    ): MailboxViewModel {
        val repo = mockk<MailRepository>(relaxed = true)
        every { repo.observeMessages() } returns MutableStateFlow(messages)
        every { repo.observeDrafts() } returns flowOf(emptyList())
        every { repo.observeOutbox() } returns flowOf(emptyList())
        accounts.forEach { account ->
            every { repo.observeFolders(account.id) } returns MutableStateFlow(folders[account.id] ?: emptyList())
        }
        val accountRepository = mockk<AccountRepository>(relaxed = true)
        every { accountRepository.observeAccounts() } returns MutableStateFlow(accounts)
        return MailboxViewModel(repo, accountRepository, syncer)
    }

    private fun account(id: String, email: String) = Account(
        id = id,
        email = email,
        displayName = email,
        authType = AuthType.PASSWORD_IMAP,
        imap = ServerConfig("imap.example.org", 993, MailSecurity.SSL_TLS),
        smtp = ServerConfig("smtp.example.org", 465, MailSecurity.SSL_TLS),
    )

    private fun folder(accountId: String, fullName: String, role: FolderRole) =
        Folder(accountId, fullName, fullName.substringAfterLast('/'), role, selectable = true)

    private fun msg(id: String, accountId: String, folder: String) = Message(
        id = id,
        accountId = accountId,
        sender = "Sender",
        senderEmail = "sender@example.org",
        subject = "Subject",
        snippet = "",
        body = "",
        isHtml = false,
        timestampMillis = 1_000L,
        isRead = false,
        isStarred = false,
        folder = folder,
        inInbox = true,
    )
}
