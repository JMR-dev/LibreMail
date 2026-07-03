// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.mailbox

import androidx.lifecycle.SavedStateHandle
import androidx.paging.PagingData
import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
import org.libremail.domain.model.ReplyMode
import org.libremail.domain.model.ServerConfig
import org.libremail.domain.model.UnreadCount
import org.libremail.domain.repository.AccountRepository
import org.libremail.domain.repository.MailRepository
import org.libremail.ui.navigation.Routes
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MailboxViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(testDispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val alice = account("imap:a", "alice@example.org")
    private val bob = account("imap:b", "bob@example.org")

    // Every mailbox mode is a single paged flow (issues #124, #214); the ViewModel's only job is to
    // dispatch each (account, folder, query) to the matching repository pager. The paged rows/scoping
    // themselves are covered by MailRepositoryImplTest and the MailboxScreen UI test.
    @Test
    fun `pagedMessages dispatches to the matching repository pager for each mode`() = runTest(testDispatcher) {
        val repo = mockk<MailRepository>(relaxed = true)
        val vm = createViewModel(accounts = listOf(alice), messages = emptyList(), repo = repo)
        backgroundScope.launch { vm.pagedMessages.collect {} }
        runCurrent()

        // Default: unified "All inboxes" browse.
        assertEquals("INBOX", vm.selectedFolder.value)
        assertNull(vm.selectedAccountId.value)
        verify { repo.pagedUnifiedFolderMessages("INBOX") }

        // A query flips the unified view to the unified search pager.
        vm.onSearchQuery("hello")
        runCurrent()
        verify { repo.pagedUnifiedSearchMessages("INBOX", "hello") }

        // Clearing the query and picking an account browses that account's folder via the per-account pager.
        vm.onSearchQuery("")
        vm.selectAccount("imap:a")
        runCurrent()
        verify { repo.pagedFolderMessages("imap:a", "INBOX") }

        // Searching within a concrete account uses the per-account search pager.
        vm.onSearchQuery("world")
        runCurrent()
        verify { repo.pagedFolderSearchMessages("imap:a", "INBOX", "world") }
    }

    @Test
    fun `selecting a concrete account pages that account's folder`() = runTest(testDispatcher) {
        val repo = mockk<MailRepository>(relaxed = true)
        val vm = createViewModel(
            accounts = listOf(alice),
            messages = listOf(msg("imap:a:INBOX:1", "imap:a", "INBOX"), msg("imap:a:Archive:1", "imap:a", "Archive")),
            repo = repo,
        )
        backgroundScope.launch { vm.pagedMessages.collect {} }

        vm.selectAccount("imap:a")
        runCurrent()

        // A concrete account uses the SQL-scoped per-account pager (issue #214), not the unified one.
        verify { repo.pagedFolderMessages("imap:a", "INBOX") }
    }

    @Test
    fun `selecting a folder scopes the view to that account and folder and syncs it`() = runTest(testDispatcher) {
        val syncer = mockk<MailSyncer>(relaxed = true)
        val repo = mockk<MailRepository>(relaxed = true)
        val vm = createViewModel(
            accounts = listOf(alice),
            messages = listOf(
                msg("imap:a:INBOX:1", "imap:a", "INBOX"),
                msg("imap:a:Archive:1", "imap:a", "Archive"),
            ),
            syncer = syncer,
            repo = repo,
        )
        backgroundScope.launch { vm.pagedMessages.collect {} }

        vm.selectFolder("imap:a", "Archive")
        runCurrent()

        assertEquals("Archive", vm.selectedFolder.value)
        assertEquals("imap:a", vm.selectedAccountId.value)
        verify { repo.pagedFolderMessages("imap:a", "Archive") }
        coVerify { syncer.syncFolder("imap:a", "Archive") }
    }

    // Issue #149: opening a per-account folder with no cached messages yet must show a spinner
    // (via isSyncingFolder) rather than "No messages to display" until the background sync settles.
    @Test
    fun `isSyncingFolder is true while the initial folder sync is in flight, then clears`() = runTest(testDispatcher) {
        val syncer = mockk<MailSyncer>()
        val gate = CompletableDeferred<Unit>()
        coEvery { syncer.syncFolder("imap:a", "Archive") } coAnswers {
            gate.await()
            Result.success(0)
        }
        val vm = createViewModel(accounts = listOf(alice), messages = emptyList(), syncer = syncer)
        backgroundScope.launch { vm.pagedMessages.collect {} }

        vm.isSyncingFolder.test {
            assertEquals(false, awaitItem())

            vm.selectFolder("imap:a", "Archive")
            assertEquals(true, awaitItem())

            gate.complete(Unit)
            assertEquals(false, awaitItem())
        }
    }

    // A failed sync must still clear the flag (try/finally), so a folder that errors out doesn't
    // spin forever. Syncer.syncFolder reports failure via Result.failure (see refresh()'s
    // onFailure handling below), not a thrown exception, so the stub mirrors that contract.
    @Test
    fun `isSyncingFolder clears even when the sync fails`() = runTest(testDispatcher) {
        val syncer = mockk<MailSyncer>()
        val gate = CompletableDeferred<Unit>()
        coEvery { syncer.syncFolder("imap:a", "Archive") } coAnswers {
            gate.await()
            Result.failure(IllegalStateException("boom"))
        }
        val vm = createViewModel(accounts = listOf(alice), messages = emptyList(), syncer = syncer)
        backgroundScope.launch { vm.pagedMessages.collect {} }

        vm.isSyncingFolder.test {
            assertEquals(false, awaitItem())

            vm.selectFolder("imap:a", "Archive")
            assertEquals(true, awaitItem())

            gate.complete(Unit)
            assertEquals(false, awaitItem())
        }
    }

    // Issue #149: switching folders before the previous folder's sync resolves must not let that
    // stale completion clear the spinner for the folder actually selected now.
    @Test
    fun `rapidly switching folders keeps the spinner for the folder actually selected`() = runTest(testDispatcher) {
        val syncer = mockk<MailSyncer>()
        val archiveGate = CompletableDeferred<Unit>()
        val sentGate = CompletableDeferred<Unit>()
        coEvery { syncer.syncFolder("imap:a", "Archive") } coAnswers {
            archiveGate.await()
            Result.success(0)
        }
        coEvery { syncer.syncFolder("imap:a", "Sent") } coAnswers {
            sentGate.await()
            Result.success(0)
        }
        val vm = createViewModel(accounts = listOf(alice), messages = emptyList(), syncer = syncer)
        backgroundScope.launch { vm.pagedMessages.collect {} }

        vm.isSyncingFolder.test {
            assertEquals(false, awaitItem())

            vm.selectFolder("imap:a", "Archive")
            assertEquals(true, awaitItem())

            // Switch away before Archive's sync resolves; still syncing (now Sent's own fetch).
            vm.selectFolder("imap:a", "Sent")
            expectNoEvents()
            assertEquals(true, vm.isSyncingFolder.value)

            // Archive's now-stale sync finishing must not clear the flag out from under Sent.
            archiveGate.complete(Unit)
            expectNoEvents()
            assertEquals(true, vm.isSyncingFolder.value)

            // Only Sent's own completion (the folder actually selected now) clears it.
            sentGate.complete(Unit)
            assertEquals(false, awaitItem())
        }
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
        backgroundScope.launch { vm.pagedMessages.collect {} }
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
                "imap:a" to
                    listOf(
                        folder("imap:a", "INBOX", FolderRole.INBOX),
                        folder("imap:a", "Archive", FolderRole.ARCHIVE),
                    ),
                "imap:b" to
                    listOf(folder("imap:b", "INBOX", FolderRole.INBOX), folder("imap:b", "Work", FolderRole.NORMAL)),
            ),
        )
        backgroundScope.launch { vm.folders.collect {} }
        // The drawer defaults to the first account.
        assertTrue(vm.folders.value.any { it.fullName == "Archive" })

        vm.setDrawerAccount("imap:b")

        assertTrue(vm.folders.value.any { it.fullName == "Work" }, "drawer should now show bob's folders")
        assertTrue(vm.folders.value.none { it.fullName == "Archive" }, "alice's folders should no longer show")
    }

    // Issue #61: switching the drawer account emits the new account immediately, while the folder
    // list keeps the old account's folders until the new account's query emits. The UI must derive
    // provider labels from the rendered folders' own accountId (providerLabelFor) so those gap
    // frames never show the old folders with the new account's brand.
    @Test
    fun `folder list lags a drawer-account switch until the new folders arrive`() = runTest(testDispatcher) {
        val repo = mockk<MailRepository>(relaxed = true)
        every { repo.observeDrafts() } returns flowOf(emptyList())
        every { repo.observeOutbox() } returns flowOf(emptyList())
        every { repo.observeFolders("imap:a") } returns
            MutableStateFlow(listOf(folder("imap:a", "INBOX", FolderRole.INBOX)))
        // Bob's folder query stays in flight (no emission yet) to reproduce the switch gap.
        val bobFolders = MutableSharedFlow<List<Folder>>()
        every { repo.observeFolders("imap:b") } returns bobFolders
        val accountRepository = mockk<AccountRepository>(relaxed = true)
        every { accountRepository.observeAccounts() } returns MutableStateFlow(listOf(alice, bob))
        val vm = MailboxViewModel(repo, accountRepository, mockk(relaxed = true), SavedStateHandle())

        vm.folders.test {
            var current = awaitItem() // the StateFlow's initial empty value, or alice's list
            while (current.isEmpty()) current = awaitItem()
            assertEquals(listOf("imap:a"), current.map { it.accountId }.distinct())

            vm.setDrawerAccount("imap:b")

            // The drawer account has already switched, but the rendered folder list has not —
            // exactly the transient frames issue #61 is about.
            assertEquals("imap:b", vm.drawerAccount.value?.id)
            assertEquals(listOf("imap:a"), vm.folders.value.map { it.accountId }.distinct())
            expectNoEvents()

            bobFolders.emit(listOf(folder("imap:b", "Work", FolderRole.NORMAL)))
            assertEquals(listOf("imap:b"), awaitItem().map { it.accountId }.distinct())
        }
    }

    @Test
    fun `toggle adds then removes a message from the selection`() = runTest(testDispatcher) {
        val vm = createViewModel(accounts = listOf(alice), messages = emptyList())

        vm.startSelection("a", "acct")
        assertEquals(setOf("a"), vm.selectedIds.value)
        vm.toggleSelection("b", "acct")
        assertEquals(setOf("a", "b"), vm.selectedIds.value)
        vm.toggleSelection("a", "acct")
        assertEquals(setOf("b"), vm.selectedIds.value)
        vm.clearSelection()
        assertTrue(vm.selectedIds.value.isEmpty())
    }

    @Test
    fun `selectAll selects every visible message`() = runTest(testDispatcher) {
        val shown = listOf(msg("imap:a:INBOX:1", "imap:a", "INBOX"), msg("imap:a:INBOX:2", "imap:a", "INBOX"))
        val vm = createViewModel(accounts = listOf(alice), messages = shown)

        // The screen passes what's shown (paged snapshot or flat list); the VM records their ids.
        vm.selectAll(shown)

        assertEquals(setOf("imap:a:INBOX:1", "imap:a:INBOX:2"), vm.selectedIds.value)
    }

    @Test
    fun `delete from a normal folder confirms then moves to trash`() = runTest(testDispatcher) {
        val repo = mockk<MailRepository>(relaxed = true)
        coEvery { repo.trash(any()) } returns Result.success(Unit)
        val vm = createViewModel(
            accounts = listOf(alice),
            messages = listOf(msg("imap:a:INBOX:1", "imap:a", "INBOX")),
            repo = repo,
        )
        backgroundScope.launch { vm.pagedMessages.collect {} }

        vm.startSelection("imap:a:INBOX:1", "imap:a")
        vm.requestDelete()
        val pending = vm.pendingConfirm.value
        assertTrue(pending is PendingAction.Delete && !pending.permanent)

        vm.confirmPending()

        coVerify { repo.trash(listOf("imap:a:INBOX:1")) }
        assertTrue(vm.selectedIds.value.isEmpty())
        assertNull(vm.pendingConfirm.value)
    }

    @Test
    fun `delete from the spam folder warns and expunges permanently`() = runTest(testDispatcher) {
        val repo = mockk<MailRepository>(relaxed = true)
        coEvery { repo.expunge(any()) } returns Result.success(Unit)
        val vm = createViewModel(
            accounts = listOf(alice),
            messages = listOf(msg("imap:a:Spam:1", "imap:a", "Spam")),
            folders = mapOf(
                "imap:a" to listOf(
                    folder("imap:a", "INBOX", FolderRole.INBOX),
                    folder("imap:a", "Spam", FolderRole.SPAM),
                ),
            ),
            repo = repo,
        )
        backgroundScope.launch { vm.pagedMessages.collect {} }
        backgroundScope.launch { vm.currentFolderRole.collect {} }
        vm.selectFolder("imap:a", "Spam")

        vm.startSelection("imap:a:Spam:1", "imap:a")
        vm.requestDelete()
        val pending = vm.pendingConfirm.value
        assertTrue(pending is PendingAction.Delete && pending.permanent)

        vm.confirmPending()

        coVerify { repo.expunge(listOf("imap:a:Spam:1")) }
    }

    @Test
    fun `spam acts only after the confirmation is accepted`() = runTest(testDispatcher) {
        val repo = mockk<MailRepository>(relaxed = true)
        coEvery { repo.reportSpam(any()) } returns Result.success(Unit)
        val vm = createViewModel(
            accounts = listOf(alice),
            messages = listOf(msg("imap:a:INBOX:1", "imap:a", "INBOX")),
            repo = repo,
        )

        vm.startSelection("imap:a:INBOX:1", "imap:a")
        vm.requestSpam()
        assertTrue(vm.pendingConfirm.value is PendingAction.Spam)
        coVerify(exactly = 0) { repo.reportSpam(any()) }

        vm.confirmPending()
        coVerify(exactly = 1) { repo.reportSpam(listOf("imap:a:INBOX:1")) }
    }

    @Test
    fun `forward builds a draft and emits OpenCompose`() = runTest(testDispatcher) {
        val repo = mockk<MailRepository>(relaxed = true)
        coEvery { repo.buildReplyDraft("imap:a:INBOX:1", ReplyMode.FORWARD) } returns Result.success("draft1")
        val vm = createViewModel(
            accounts = listOf(alice),
            messages = listOf(msg("imap:a:INBOX:1", "imap:a", "INBOX")),
            repo = repo,
        )

        vm.startSelection("imap:a:INBOX:1", "imap:a")
        vm.reply(ReplyMode.FORWARD)

        assertEquals(MailboxEvent.OpenCompose("draft1"), vm.events.first())
        coVerify { repo.buildReplyDraft("imap:a:INBOX:1", ReplyMode.FORWARD) }
    }

    @Test
    fun `archive delegates the selection to the repository and exits selection`() = runTest(testDispatcher) {
        val repo = mockk<MailRepository>(relaxed = true)
        coEvery { repo.archive(any()) } returns Result.success(Unit)
        val vm = createViewModel(
            accounts = listOf(alice),
            messages = listOf(msg("imap:a:INBOX:1", "imap:a", "INBOX")),
            repo = repo,
        )

        vm.startSelection("imap:a:INBOX:1", "imap:a")
        vm.archiveSelected()

        coVerify { repo.archive(listOf("imap:a:INBOX:1")) }
        assertTrue(vm.selectedIds.value.isEmpty())
    }

    @Test
    fun `reply opens a prefilled draft for the single selection`() = runTest(testDispatcher) {
        val repo = mockk<MailRepository>(relaxed = true)
        coEvery { repo.buildReplyDraft("imap:a:INBOX:1", ReplyMode.REPLY) } returns Result.success("d2")
        val vm = createViewModel(
            accounts = listOf(alice),
            messages = listOf(msg("imap:a:INBOX:1", "imap:a", "INBOX")),
            repo = repo,
        )

        vm.startSelection("imap:a:INBOX:1", "imap:a")
        vm.reply(ReplyMode.REPLY)

        assertEquals(MailboxEvent.OpenCompose("d2"), vm.events.first())
    }

    @Test
    fun `dismissing a pending confirmation cancels the action`() = runTest(testDispatcher) {
        val repo = mockk<MailRepository>(relaxed = true)
        val vm = createViewModel(
            accounts = listOf(alice),
            messages = listOf(msg("imap:a:INBOX:1", "imap:a", "INBOX")),
            repo = repo,
        )
        vm.startSelection("imap:a:INBOX:1", "imap:a")

        vm.requestSpam()
        vm.dismissConfirm()
        assertNull(vm.pendingConfirm.value)

        vm.confirmPending() // nothing pending — no-op
        coVerify(exactly = 0) { repo.reportSpam(any()) }
    }

    @Test
    fun `move is enabled for a single-account selection and disabled across accounts`() = runTest(testDispatcher) {
        val vm = createViewModel(
            accounts = listOf(alice, bob),
            messages = listOf(msg("imap:a:INBOX:1", "imap:a", "INBOX"), msg("imap:b:INBOX:1", "imap:b", "INBOX")),
        )
        backgroundScope.launch { vm.pagedMessages.collect {} }
        backgroundScope.launch { vm.canMove.collect {} }

        vm.startSelection("imap:a:INBOX:1", "imap:a")
        assertEquals(true, vm.canMove.value)

        vm.toggleSelection("imap:b:INBOX:1", "imap:b")
        assertEquals(false, vm.canMove.value)
    }

    @Test
    fun `move sends the selection to the chosen folder`() = runTest(testDispatcher) {
        val repo = mockk<MailRepository>(relaxed = true)
        coEvery { repo.moveToFolder(any(), any()) } returns Result.success(Unit)
        val vm = createViewModel(
            accounts = listOf(alice),
            messages = listOf(msg("imap:a:INBOX:1", "imap:a", "INBOX")),
            repo = repo,
        )

        vm.startSelection("imap:a:INBOX:1", "imap:a")
        vm.moveSelected("Receipts")

        coVerify { repo.moveToFolder(listOf("imap:a:INBOX:1"), "Receipts") }
        assertTrue(vm.selectedIds.value.isEmpty())
    }

    @Test
    fun `reply all confirms first, then opens a prefilled draft`() = runTest(testDispatcher) {
        val repo = mockk<MailRepository>(relaxed = true)
        coEvery { repo.buildReplyDraft("imap:a:INBOX:1", ReplyMode.REPLY_ALL) } returns Result.success("d1")
        val vm = createViewModel(
            accounts = listOf(alice),
            messages = listOf(msg("imap:a:INBOX:1", "imap:a", "INBOX")),
            repo = repo,
        )
        vm.startSelection("imap:a:INBOX:1", "imap:a")

        vm.requestReplyAll()
        assertTrue(vm.pendingConfirm.value is PendingAction.ReplyAll)
        coVerify(exactly = 0) { repo.buildReplyDraft(any(), any()) }

        vm.confirmPending()
        assertEquals(MailboxEvent.OpenCompose("d1"), vm.events.first())
        coVerify { repo.buildReplyDraft("imap:a:INBOX:1", ReplyMode.REPLY_ALL) }
    }

    @Test
    fun `opens filtered to the account passed as a nav argument`() = runTest(testDispatcher) {
        val repo = mockk<MailRepository>(relaxed = true)
        val vm = createViewModel(
            accounts = listOf(alice, bob),
            messages = listOf(msg("imap:a:INBOX:1", "imap:a", "INBOX"), msg("imap:b:INBOX:1", "imap:b", "INBOX")),
            initialAccountId = "imap:a",
            repo = repo,
        )
        backgroundScope.launch { vm.pagedMessages.collect {} }
        runCurrent()

        assertEquals("imap:a", vm.selectedAccountId.value)
        assertEquals("INBOX", vm.selectedFolder.value)
        // Seeded to a concrete account, so it pages that account's inbox (not the unified pager).
        verify { repo.pagedFolderMessages("imap:a", "INBOX") }
    }

    @Test
    fun `folderUnreadCounts maps the drawer account's folders to their unread counts`() = runTest(testDispatcher) {
        val vm = createViewModel(
            accounts = listOf(alice, bob),
            messages = emptyList(),
            unreadCounts = listOf(
                UnreadCount("imap:a", "INBOX", 3),
                UnreadCount("imap:a", "[Gmail]/Spam", 1),
                UnreadCount("imap:b", "INBOX", 9),
            ),
        )
        backgroundScope.launch { vm.folderUnreadCounts.collect {} }

        // The drawer defaults to the first account (alice); bob's counts are excluded.
        assertEquals(mapOf("INBOX" to 3, "[Gmail]/Spam" to 1), vm.folderUnreadCounts.value)
    }

    @Test
    fun `folderUnreadCounts follows the drawer-account switch`() = runTest(testDispatcher) {
        val vm = createViewModel(
            accounts = listOf(alice, bob),
            messages = emptyList(),
            unreadCounts = listOf(UnreadCount("imap:a", "INBOX", 3), UnreadCount("imap:b", "INBOX", 9)),
        )
        backgroundScope.launch { vm.folderUnreadCounts.collect {} }

        vm.setDrawerAccount("imap:b")

        assertEquals(mapOf("INBOX" to 9), vm.folderUnreadCounts.value)
    }

    @Test
    fun `accountsWithUnread lists every account that has unread mail in any folder`() = runTest(testDispatcher) {
        val vm = createViewModel(
            accounts = listOf(alice, bob),
            messages = emptyList(),
            // alice has unread mail (in a non-inbox folder too); bob has none.
            unreadCounts = listOf(UnreadCount("imap:a", "INBOX", 3), UnreadCount("imap:a", "Archive", 2)),
        )
        backgroundScope.launch { vm.accountsWithUnread.collect {} }

        assertEquals(setOf("imap:a"), vm.accountsWithUnread.value)
    }

    private fun createViewModel(
        accounts: List<Account>,
        messages: List<Message>,
        folders: Map<String, List<Folder>> = emptyMap(),
        unreadCounts: List<UnreadCount> = emptyList(),
        syncer: MailSyncer = mockk(relaxed = true),
        repo: MailRepository = mockk(relaxed = true),
        initialAccountId: String? = null,
    ): MailboxViewModel {
        // Every mailbox mode is paged (issues #124, #214). Mirror each DAO projection as a single static
        // page over the seed list: browse pagers keep folder-synced (inInbox) rows; search pagers match
        // the same fields the DAO's LIKE query does, leaving inInbox unfiltered.
        every { repo.pagedUnifiedFolderMessages(any()) } answers {
            val folder = firstArg<String>()
            flowOf(PagingData.from(messages.filter { it.folder == folder && it.inInbox }))
        }
        every { repo.pagedFolderMessages(any(), any()) } answers {
            val accountId = firstArg<String>()
            val folder = secondArg<String>()
            flowOf(PagingData.from(messages.filter { it.accountId == accountId && it.folder == folder && it.inInbox }))
        }
        every { repo.pagedUnifiedSearchMessages(any(), any()) } answers {
            val folder = firstArg<String>()
            val query = secondArg<String>()
            flowOf(PagingData.from(messages.filter { it.folder == folder && it.matchesSearch(query) }))
        }
        every { repo.pagedFolderSearchMessages(any(), any(), any()) } answers {
            val accountId = firstArg<String>()
            val folder = secondArg<String>()
            val query = thirdArg<String>()
            flowOf(
                PagingData.from(
                    messages.filter { it.accountId == accountId && it.folder == folder && it.matchesSearch(query) },
                ),
            )
        }
        every { repo.observeDrafts() } returns flowOf(emptyList())
        every { repo.observeOutbox() } returns flowOf(emptyList())
        every { repo.observeUnreadCounts() } returns MutableStateFlow(unreadCounts)
        accounts.forEach { account ->
            every { repo.observeFolders(account.id) } returns MutableStateFlow(folders[account.id] ?: emptyList())
        }
        val accountRepository = mockk<AccountRepository>(relaxed = true)
        every { accountRepository.observeAccounts() } returns MutableStateFlow(accounts)
        val savedState = initialAccountId?.let {
            SavedStateHandle(mapOf(Routes.MAILBOX_ARG_ACCOUNT to it))
        } ?: SavedStateHandle()
        return MailboxViewModel(repo, accountRepository, syncer, savedState)
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

/** Mirrors the paged-search DAO queries' columns so the stubbed search pagers filter like production. */
private fun Message.matchesSearch(query: String): Boolean = sender.contains(query, ignoreCase = true) ||
    senderEmail.contains(query, ignoreCase = true) ||
    subject.contains(query, ignoreCase = true) ||
    snippet.contains(query, ignoreCase = true)
