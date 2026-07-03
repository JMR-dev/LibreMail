// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.mailbox

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.data.sync.Syncer
import org.libremail.domain.model.Account
import org.libremail.domain.model.AuthType
import org.libremail.domain.model.MailSecurity
import org.libremail.domain.model.ReplyMode
import org.libremail.domain.model.ServerConfig
import org.libremail.domain.repository.AccountRepository
import org.libremail.domain.repository.MailRepository
import org.libremail.ui.navigation.Routes
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the parts of [MailboxViewModel] the behaviour-focused [MailboxViewModelTest] leaves out:
 * pull-to-refresh, search open/close, the drawer-refresh hooks, the failure branches of the selection
 * actions, and the account-removal fallback.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MailboxViewModelExtraTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val alice = account("imap:a", "alice@example.org")
    private val bob = account("imap:b", "bob@example.org")

    private class Fixture(
        val vm: MailboxViewModel,
        val repo: MailRepository,
        val syncer: Syncer,
        val accountsFlow: MutableStateFlow<List<Account>>,
    )

    private fun fixture(
        accounts: List<Account> = listOf(alice),
        initialAccountId: String? = null,
        repo: MailRepository = mockk(relaxed = true),
        syncer: Syncer = mockk(relaxed = true),
    ): Fixture {
        every { repo.observeDrafts() } returns flowOf(emptyList())
        every { repo.observeOutbox() } returns flowOf(emptyList())
        every { repo.observeUnreadCounts() } returns flowOf(emptyList())
        every { repo.observeFolders(any()) } returns flowOf(emptyList())
        coEvery { syncer.syncAll() } returns Result.success(0)
        coEvery { syncer.syncFolder(any(), any()) } returns Result.success(0)
        val accountsFlow = MutableStateFlow(accounts)
        val accountRepository = mockk<AccountRepository>(relaxed = true)
        every { accountRepository.observeAccounts() } returns accountsFlow
        val savedState = initialAccountId?.let { SavedStateHandle(mapOf(Routes.MAILBOX_ARG_ACCOUNT to it)) }
            ?: SavedStateHandle()
        return Fixture(MailboxViewModel(repo, accountRepository, syncer, savedState), repo, syncer, accountsFlow)
    }

    @Test
    fun `pull-to-refresh on the unified inbox syncs every account and toggles the spinner`() = runTest(dispatcher) {
        val f = fixture()
        val gate = CompletableDeferred<Result<Int>>()
        coEvery { f.syncer.syncAll() } coAnswers { gate.await() } // override the fixture's default

        f.vm.isRefreshing.test {
            assertFalse(awaitItem())
            f.vm.refresh()
            assertTrue(awaitItem()) // spinner up while the gated sync runs
            gate.complete(Result.success(0))
            assertFalse(awaitItem())
        }
        coVerify(exactly = 1) { f.syncer.syncAll() }
    }

    @Test
    fun `pull-to-refresh on a specific account syncs just that folder`() = runTest(dispatcher) {
        val f = fixture()
        f.vm.selectAccount("imap:a")
        runCurrent()

        f.vm.refresh()
        advanceUntilIdle()

        coVerify { f.syncer.syncFolder("imap:a", "INBOX") }
    }

    @Test
    fun `a refresh already in flight is not started again`() = runTest(dispatcher) {
        val f = fixture()
        val gate = CompletableDeferred<Result<Int>>()
        coEvery { f.syncer.syncAll() } coAnswers { gate.await() }

        f.vm.refresh() // in flight, suspended on the gate
        f.vm.refresh() // guarded out
        gate.complete(Result.success(0))
        advanceUntilIdle()

        coVerify(exactly = 1) { f.syncer.syncAll() }
    }

    @Test
    fun `a failed refresh surfaces the error, then consumeError clears it`() = runTest(dispatcher) {
        val f = fixture()
        coEvery { f.syncer.syncAll() } returns Result.failure(IllegalStateException("no network"))

        f.vm.refresh()
        advanceUntilIdle()
        assertEquals("no network", f.vm.error.value)

        f.vm.consumeError()
        assertNull(f.vm.error.value)
    }

    @Test
    fun `opening search clears any selection and closing it wipes the query and server hits`() = runTest(dispatcher) {
        val f = fixture()
        f.vm.startSelection("imap:a:INBOX:1", "imap:a")

        f.vm.openSearch()
        assertTrue(f.vm.searchActive.value)
        assertTrue(f.vm.selectedIds.value.isEmpty())

        f.vm.onSearchQuery("hello")
        f.vm.closeSearch()
        advanceUntilIdle()

        assertFalse(f.vm.searchActive.value)
        assertEquals("", f.vm.searchQuery.value)
        coVerify { f.repo.clearSearchResults() }
    }

    @Test
    fun `a debounced search query fetches server-side matches for the current folder`() = runTest(dispatcher) {
        val f = fixture()

        f.vm.onSearchQuery("meeting")
        advanceUntilIdle() // cross the search debounce window

        coVerify { f.repo.searchServer("meeting", null, "INBOX") }
    }

    @Test
    fun `opening the drawer refreshes the current drawer account's folders`() = runTest(dispatcher) {
        val f = fixture()
        backgroundScope.launch { f.vm.drawerAccount.collect {} }
        runCurrent()

        f.vm.onDrawerOpened()
        advanceUntilIdle()

        coVerify { f.repo.refreshFolders("imap:a") }
    }

    @Test
    fun `setDrawerAccount points the drawer elsewhere and refreshes those folders`() = runTest(dispatcher) {
        val f = fixture(accounts = listOf(alice, bob))

        f.vm.setDrawerAccount("imap:b")
        advanceUntilIdle()

        coVerify { f.repo.refreshFolders("imap:b") }
    }

    @Test
    fun `a failed selection action surfaces the error and exits selection`() = runTest(dispatcher) {
        val f = fixture()
        coEvery { f.repo.archive(any()) } returns Result.failure(RuntimeException("IMAP move failed"))
        f.vm.startSelection("imap:a:INBOX:1", "imap:a")

        f.vm.archiveSelected()
        advanceUntilIdle()

        assertEquals("IMAP move failed", f.vm.error.value)
        assertTrue(f.vm.selectedIds.value.isEmpty())
    }

    @Test
    fun `a failed reply build surfaces the error`() = runTest(dispatcher) {
        val f = fixture()
        coEvery { f.repo.buildReplyDraft(any(), any()) } returns Result.failure(RuntimeException("draft failed"))
        f.vm.startSelection("imap:a:INBOX:1", "imap:a")

        f.vm.reply(ReplyMode.FORWARD)
        advanceUntilIdle()

        assertEquals("draft failed", f.vm.error.value)
        assertFalse(f.vm.actionInProgress.value)
    }

    @Test
    fun `selection actions no-op without a selection`() = runTest(dispatcher) {
        val f = fixture()

        f.vm.requestSpam()
        f.vm.requestDelete()
        f.vm.requestReplyAll()
        f.vm.reply(ReplyMode.REPLY)

        assertNull(f.vm.pendingConfirm.value)
        coVerify(exactly = 0) { f.repo.buildReplyDraft(any(), any()) }
    }

    @Test
    fun `removing the filtered account falls back to the unified inbox`() = runTest(dispatcher) {
        val f = fixture(accounts = listOf(alice, bob), initialAccountId = "imap:b")
        backgroundScope.launch { f.vm.accounts.collect {} }
        runCurrent()
        assertEquals("imap:b", f.vm.selectedAccountId.value)

        // Bob's account is deleted; the filter must fall back rather than point at a gone account.
        f.accountsFlow.value = listOf(alice)
        runCurrent()

        assertNull(f.vm.selectedAccountId.value)
        assertEquals("INBOX", f.vm.selectedFolder.value)
    }

    @Test
    fun `draft and outbox counts follow their streams`() = runTest(dispatcher) {
        val repo = mockk<MailRepository>(relaxed = true)
        every { repo.observeDrafts() } returns flowOf(listOf(mockk(), mockk()))
        every { repo.observeOutbox() } returns flowOf(listOf(mockk()))
        every { repo.observeUnreadCounts() } returns flowOf(emptyList())
        every { repo.observeFolders(any()) } returns flowOf(emptyList())
        val accountRepository = mockk<AccountRepository>(relaxed = true)
        every { accountRepository.observeAccounts() } returns MutableStateFlow(listOf(alice))
        val vm = MailboxViewModel(repo, accountRepository, mockk(relaxed = true), SavedStateHandle())

        backgroundScope.launch { vm.draftCount.collect {} }
        backgroundScope.launch { vm.outboxCount.collect {} }
        backgroundScope.launch { vm.hasAccounts.collect {} }
        runCurrent()

        assertEquals(2, vm.draftCount.value)
        assertEquals(1, vm.outboxCount.value)
        assertTrue(vm.hasAccounts.value)
    }

    @Test
    fun `mailbox events and pending actions carry value semantics`() {
        val open = MailboxEvent.OpenCompose("draft-1")
        val (draftId) = open
        assertEquals("draft-1", draftId)
        assertEquals(open, open.copy())
        assertEquals(open.hashCode(), open.copy().hashCode())
        assertTrue(open.toString().contains("draft-1"))
        assertNotEquals(open, MailboxEvent.OpenCompose("draft-2"))

        val delete = PendingAction.Delete(count = 3, permanent = true)
        val (count, permanent) = delete
        assertEquals(3, count)
        assertTrue(permanent)
        assertEquals(delete, delete.copy())
        assertEquals(delete.hashCode(), delete.copy().hashCode())
        assertNotEquals(delete, delete.copy(permanent = false))
        assertNotEquals(delete, delete.copy(count = 4))
        assertNotEquals<PendingAction>(delete, PendingAction.Spam(3))

        val spam = PendingAction.Spam(2)
        assertEquals(2, spam.count)
        assertEquals(spam, spam.copy())
        assertNotEquals(spam, spam.copy(count = 5))
        assertTrue(spam.toString().contains("2"))

        val replyAll = PendingAction.ReplyAll("m1")
        assertEquals("m1", replyAll.messageId)
        assertEquals(replyAll, replyAll.copy())
        assertNotEquals<PendingAction>(replyAll, PendingAction.ReplyAll("m2"))
        assertTrue(replyAll.toString().contains("m1"))
    }

    private fun account(id: String, email: String) = Account(
        id = id,
        email = email,
        displayName = email,
        authType = AuthType.PASSWORD_IMAP,
        imap = ServerConfig("imap.example.org", 993, MailSecurity.SSL_TLS),
        smtp = ServerConfig("smtp.example.org", 465, MailSecurity.SSL_TLS),
    )
}
