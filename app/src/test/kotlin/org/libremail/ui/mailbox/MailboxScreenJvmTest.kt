// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.mailbox

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.SavedStateHandle
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.R
import org.libremail.data.sync.Syncer
import org.libremail.domain.model.Account
import org.libremail.domain.model.AuthType
import org.libremail.domain.model.Draft
import org.libremail.domain.model.Folder
import org.libremail.domain.model.FolderRole
import org.libremail.domain.model.MailSecurity
import org.libremail.domain.model.Message
import org.libremail.domain.model.OutboxMessage
import org.libremail.domain.model.ServerConfig
import org.libremail.domain.model.UnreadCount
import org.libremail.domain.repository.AccountRepository
import org.libremail.domain.repository.MailRepository
import org.libremail.ui.theme.LibreMailTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric JVM port of the instrumented [MailboxScreenTest] (batch 8/9 of umbrella #373): drives
 * the real [MailboxScreen] + [MailboxViewModel] over a mocked [MailRepository]/[AccountRepository]/
 * [Syncer] on the JVM via the v2 `createComposeRule()` — no emulator — so [MailboxScreen]'s render +
 * interaction code counts toward JaCoCo's JVM-testable surface. Paging 3 is fed on the JVM by handing
 * each repository pager a static [PagingData.from] flow (the same technique as [MailboxViewModelTest]);
 * no real Room/Paging source stands up. Covers the no-accounts welcome fallback, the populated list
 * (sender/subject/snippet, offline badge, unified per-account labels + filter chips, drafts/outbox
 * entries), the empty/loading/no-results states, opening/closing search, the multi-select contextual
 * action bar (overflow, archive/spam/delete with their confirm dialogs, move picker, and the
 * archive-hidden-in-archive branch), and the disambiguated app-bar title. The instrumented
 * [MailboxScreenTest] stays as the on-device E2E. See
 * [org.libremail.ui.onboarding.AddAnotherAccountScreenJvmTest] for the pattern.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class MailboxScreenJvmTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun string(resId: Int) = context.getString(resId)
    private fun string(resId: Int, arg: Int) = context.getString(resId, arg)

    private val account = Account(
        id = "imap:a",
        email = "a@example.org",
        displayName = "A",
        authType = AuthType.PASSWORD_IMAP,
        imap = ServerConfig("imap.example.org", 993, MailSecurity.SSL_TLS),
        smtp = ServerConfig("smtp.example.org", 465, MailSecurity.SSL_TLS),
    )
    private val bob = account.copy(id = "imap:b", email = "b@example.org", displayName = "B")

    private fun message(
        uid: String,
        subject: String,
        snippet: String = "",
        bodyFetched: Boolean = false,
        folder: String = "INBOX",
        accountId: String = "imap:a",
        sender: String = "Sender $uid",
    ) = Message(
        id = "$accountId:$folder:$uid",
        accountId = accountId,
        sender = sender,
        senderEmail = "s$uid@example.org",
        subject = subject,
        snippet = snippet,
        body = "",
        isHtml = false,
        timestampMillis = 1_000L,
        isRead = true,
        isStarred = false,
        folder = folder,
        inInbox = true,
        bodyFetched = bodyFetched,
    )

    // --- Tests -----------------------------------------------------------------------------------

    @Test
    fun noAccounts_showsWelcomeFallback_andInvokesAddAccount() {
        var addAccountTapped = false
        setContent(accounts = emptyList(), onAddAccount = { addAccountTapped = true })

        // With no accounts the mailbox falls back to the onboarding welcome invitation.
        composeTestRule.onNodeWithText(string(R.string.onboarding_welcome_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.onboarding_add_account)).performClick()

        assertTrue(addAccountTapped)
    }

    @Test
    fun populatedInbox_rendersSenderSubjectAndSnippet() {
        setContent(
            messages = listOf(
                message("1", subject = "Lunch plans", snippet = "See you at noon", sender = "Alice"),
            ),
        )
        waitForText("Lunch plans")

        composeTestRule.onNodeWithText("Alice").assertIsDisplayed()
        composeTestRule.onNodeWithText("Lunch plans").assertIsDisplayed()
        composeTestRule.onNodeWithText("See you at noon").assertIsDisplayed()
    }

    @Test
    fun offlineIndicator_showsForCachedMessages() {
        setContent(messages = listOf(message("1", subject = "Cached", bodyFetched = true)))
        waitForText("Cached")

        composeTestRule.onNodeWithContentDescription(string(R.string.message_available_offline)).assertIsDisplayed()
    }

    @Test
    fun multipleAccounts_showAccountFilterRow_andPerRowAccountLabels() {
        setContent(
            accounts = listOf(account, bob),
            messages = listOf(
                message("1", subject = "From A", accountId = "imap:a"),
                message("2", subject = "From B", accountId = "imap:b"),
            ),
        )
        waitForText("From A")

        // With 2+ accounts on the unified inbox, the all-accounts filter chip heads the chip row.
        composeTestRule.onNodeWithText(string(R.string.mailbox_all_accounts)).assertIsDisplayed()
        // Each message is tagged with its owning account's address (the unified-inbox account label).
        assertTrue(composeTestRule.onAllNodesWithText("a@example.org").fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeTestRule.onAllNodesWithText("b@example.org").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun draftsAndOutboxEntries_showAtInbox_andNavigate() {
        var openedDrafts = false
        var openedOutbox = false
        setContent(
            messages = listOf(message("1", subject = "Msg")),
            draftCount = 2,
            outboxCount = 1,
            onOpenDrafts = { openedDrafts = true },
            onOpenOutbox = { openedOutbox = true },
        )
        waitForText(string(R.string.drafts_count, 2))

        composeTestRule.onNodeWithText(string(R.string.drafts_count, 2)).performClick()
        assertTrue(openedDrafts)
        composeTestRule.onNodeWithText(string(R.string.outbox_count, 1)).performClick()
        assertTrue(openedOutbox)
    }

    @Test
    fun emptyInbox_showsNoMessagesState_onceThePagerSettlesEmpty() {
        setContent(pagedOverride = settledEmpty())

        waitForText(string(R.string.mailbox_empty))
        composeTestRule.onNodeWithText(string(R.string.mailbox_empty)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.mailbox_pull_to_refresh)).assertIsDisplayed()
    }

    // Issue #219: the empty state must stay hidden while the pager is still refreshing, so
    // "No messages yet" never flashes on return from the reader. This frozen refresh == Loading pager
    // never reaches the "settled" gate, so the string can never appear.
    @Test
    fun emptyInbox_hidesNoMessagesState_whileThePagerIsStillLoading() {
        setContent(pagedOverride = loadingEmpty())

        composeTestRule.onNodeWithText(string(R.string.mailbox_empty)).assertDoesNotExist()
    }

    @Test
    fun openingSearch_showsHintField_andCloseReturnsToTitle() {
        setContent(messages = listOf(message("1", subject = "Msg")))
        waitForText("Msg")

        composeTestRule.onNodeWithContentDescription(string(R.string.search)).performClick()
        waitForText(string(R.string.search_hint))
        composeTestRule.onNodeWithText(string(R.string.search_hint)).assertIsDisplayed()

        // The nav icon becomes a Close while searching; tapping it tears the search field down and
        // brings the search action back. (The "Mailbox" title is shared with the bottom-nav tab label,
        // so key off the search field rather than the ambiguous title text.)
        composeTestRule.onNodeWithContentDescription(string(R.string.search_close)).performClick()
        composeTestRule.waitUntil(TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(string(R.string.search_hint)).fetchSemanticsNodes().isEmpty()
        }
        composeTestRule.onNodeWithText(string(R.string.search_hint)).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(string(R.string.search)).assertIsDisplayed()
    }

    @Test
    fun searchWithNoMatches_showsNoResultsState() {
        // Both browse and search pagers are settled-empty, so the visible empty state is driven purely
        // by the search flags (StateFlow) flipping the gate from "no messages" to "no results" — no
        // reliance on a paging switch delivering different content.
        val viewModel = setContent(pagedOverride = settledEmpty(), searchOverride = settledEmpty())
        waitForText(string(R.string.mailbox_empty))

        // Drive the VM directly (an established pattern in the instrumented test): open search, type a
        // query with no matches.
        viewModel.openSearch()
        viewModel.onSearchQuery("zzz")

        waitForText(string(R.string.search_no_results))
        composeTestRule.onNodeWithText(string(R.string.search_no_results)).assertIsDisplayed()
    }

    @Test
    fun longPress_entersSelection_andTapTracksTheCount() {
        setContent(messages = listOf(message("1", subject = "First"), message("2", subject = "Second")))
        waitForText("First")

        composeTestRule.onNodeWithText("First").performTouchInput { longClick() }
        waitForText(string(R.string.cab_selected_count, 1))
        composeTestRule.onNodeWithText(string(R.string.cab_selected_count, 1)).assertIsDisplayed()

        composeTestRule.onNodeWithText("Second").performClick()
        waitForText(string(R.string.cab_selected_count, 2))
        composeTestRule.onNodeWithText(string(R.string.cab_selected_count, 2)).assertIsDisplayed()
    }

    @Test
    fun overflow_showsReplyActions_forSingleSelection() {
        val viewModel = setContent(messages = listOf(message("1", subject = "First")))
        waitForText("First")
        viewModel.startSelection("imap:a:INBOX:1", "imap:a")
        waitForText(string(R.string.cab_selected_count, 1))

        composeTestRule.onNodeWithContentDescription(string(R.string.action_more)).performClick()

        composeTestRule.onNodeWithText(string(R.string.action_reply)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.action_reply_all)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.action_forward)).assertIsDisplayed()
    }

    @Test
    fun overflow_hidesReplyActions_forMultiSelection() {
        val viewModel = setContent(messages = listOf(message("1", subject = "First"), message("2", subject = "Second")))
        waitForText("First")
        viewModel.startSelection("imap:a:INBOX:1", "imap:a")
        viewModel.toggleSelection("imap:a:INBOX:2", "imap:a")
        waitForText(string(R.string.cab_selected_count, 2))

        composeTestRule.onNodeWithContentDescription(string(R.string.action_more)).performClick()

        composeTestRule.onNodeWithText(string(R.string.action_select_all)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.action_reply)).assertDoesNotExist()
        composeTestRule.onNodeWithText(string(R.string.action_forward)).assertDoesNotExist()
    }

    @Test
    fun archiveIcon_isDirect_andArchivesTheSelection() {
        val archived = mutableListOf<List<String>>()
        val repo = mockk<MailRepository>(relaxed = true)
        coEvery { repo.archive(any()) } answers {
            archived += firstArg<List<String>>()
            Result.success(Unit)
        }
        val viewModel = setContent(messages = listOf(message("1", subject = "First")), repo = repo)
        waitForText("First")
        viewModel.startSelection("imap:a:INBOX:1", "imap:a")
        waitForText(string(R.string.cab_selected_count, 1))

        // A direct icon button — no trip through the overflow menu.
        composeTestRule.onNodeWithContentDescription(string(R.string.action_archive)).performClick()

        composeTestRule.waitUntil(TIMEOUT_MS) { archived.isNotEmpty() }
        assertEquals(listOf("imap:a:INBOX:1"), archived.first())
    }

    @Test
    fun spamIcon_confirmsBeforeReporting() {
        val spammed = mutableListOf<List<String>>()
        val repo = mockk<MailRepository>(relaxed = true)
        coEvery { repo.reportSpam(any()) } answers {
            spammed += firstArg<List<String>>()
            Result.success(Unit)
        }
        val viewModel = setContent(messages = listOf(message("1", subject = "First")), repo = repo)
        waitForText("First")
        viewModel.startSelection("imap:a:INBOX:1", "imap:a")
        waitForText(string(R.string.cab_selected_count, 1))

        composeTestRule.onNodeWithContentDescription(string(R.string.action_spam)).performClick()
        composeTestRule.onNodeWithText(string(R.string.confirm_spam_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.action_move)).performClick()

        composeTestRule.waitUntil(TIMEOUT_MS) { spammed.isNotEmpty() }
        assertEquals(listOf("imap:a:INBOX:1"), spammed.first())
    }

    @Test
    fun deleteIcon_confirmsMoveToTrash_thenTrashes() {
        val trashed = mutableListOf<List<String>>()
        val repo = mockk<MailRepository>(relaxed = true)
        coEvery { repo.trash(any()) } answers {
            trashed += firstArg<List<String>>()
            Result.success(Unit)
        }
        val viewModel = setContent(messages = listOf(message("1", subject = "First")), repo = repo)
        waitForText("First")
        viewModel.startSelection("imap:a:INBOX:1", "imap:a")
        waitForText(string(R.string.cab_selected_count, 1))

        composeTestRule.onNodeWithContentDescription(string(R.string.action_delete)).performClick()
        composeTestRule.onNodeWithText(string(R.string.confirm_trash_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.action_move)).performClick()

        composeTestRule.waitUntil(TIMEOUT_MS) { trashed.isNotEmpty() }
        assertEquals(listOf("imap:a:INBOX:1"), trashed.first())
    }

    @Test
    fun cancellingAConfirmation_dismissesWithoutActing() {
        val spammed = mutableListOf<List<String>>()
        val repo = mockk<MailRepository>(relaxed = true)
        coEvery { repo.reportSpam(any()) } answers {
            spammed += firstArg<List<String>>()
            Result.success(Unit)
        }
        val viewModel = setContent(messages = listOf(message("1", subject = "First")), repo = repo)
        waitForText("First")
        viewModel.startSelection("imap:a:INBOX:1", "imap:a")
        waitForText(string(R.string.cab_selected_count, 1))

        composeTestRule.onNodeWithContentDescription(string(R.string.action_spam)).performClick()
        composeTestRule.onNodeWithText(string(R.string.confirm_spam_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.cancel)).performClick()

        composeTestRule.onNodeWithText(string(R.string.confirm_spam_title)).assertDoesNotExist()
        assertTrue(spammed.isEmpty())
    }

    @Test
    fun movePicker_movesSelectionToTheChosenFolder() {
        val moved = mutableListOf<Pair<List<String>, String>>()
        val repo = mockk<MailRepository>(relaxed = true)
        coEvery { repo.moveToFolder(any(), any()) } answers {
            moved += firstArg<List<String>>() to secondArg<String>()
            Result.success(Unit)
        }
        val viewModel = setContent(
            messages = listOf(message("1", subject = "First")),
            folders = mapOf(
                "imap:a" to listOf(
                    Folder("imap:a", "INBOX", "INBOX", FolderRole.INBOX, selectable = true),
                    Folder("imap:a", "Receipts", "Receipts", FolderRole.NORMAL, selectable = true),
                ),
            ),
            repo = repo,
        )
        waitForText("First")
        viewModel.startSelection("imap:a:INBOX:1", "imap:a")
        waitForText(string(R.string.cab_selected_count, 1))

        composeTestRule.onNodeWithContentDescription(string(R.string.action_more)).performClick()
        composeTestRule.onNodeWithText(string(R.string.action_move)).performClick()
        // The off-screen navigation drawer also lists "Receipts", so scope the tap to the move dialog.
        composeTestRule.onNode(hasText("Receipts") and hasAnyAncestor(isDialog())).performClick()

        composeTestRule.waitUntil(TIMEOUT_MS) { moved.isNotEmpty() }
        assertEquals("Receipts", moved.first().second)
    }

    @Test
    fun archiveIcon_hides_whileViewingTheArchiveFolder() {
        // The archive-hidden branch keys off currentFolderRole (a StateFlow combine of folders +
        // selectedFolder), not the paged list, so the selection can be seeded via the VM without
        // depending on a paging switch delivering the folder's rows.
        val viewModel = setContent(
            folders = mapOf(
                "imap:a" to listOf(
                    Folder("imap:a", "INBOX", "INBOX", FolderRole.INBOX, selectable = true),
                    Folder("imap:a", "Archive", "Archive", FolderRole.ARCHIVE, selectable = true),
                ),
            ),
        )
        viewModel.selectFolder("imap:a", "Archive")
        viewModel.startSelection("imap:a:Archive:1", "imap:a")

        // Wait for the ARCHIVE role to propagate: the archive action drops out of the contextual bar
        // (Archive/Spam are hidden while already viewing that role's folder).
        composeTestRule.waitUntil(TIMEOUT_MS) {
            composeTestRule.onAllNodesWithContentDescription(string(R.string.action_archive))
                .fetchSemanticsNodes().isEmpty()
        }
        composeTestRule.onNodeWithContentDescription(string(R.string.action_spam)).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(string(R.string.action_delete)).assertIsDisplayed()
    }

    @Test
    fun appBarTitle_showsTheSelectedFolderFriendlyLabel() {
        // The title resolves from selectedFolder + folders (both StateFlow), independent of the paged
        // list, so no message rows are needed.
        val viewModel = setContent(
            folders = mapOf(
                "imap:a" to listOf(
                    Folder("imap:a", "INBOX", "INBOX", FolderRole.INBOX, selectable = true),
                    Folder("imap:a", "[Gmail]/Sent Mail", "Sent Mail", FolderRole.SENT, selectable = true),
                ),
            ),
        )
        viewModel.selectFolder("imap:a", "[Gmail]/Sent Mail")

        // Once selected, the friendly "Sent" label renders in BOTH the drawer entry and the app-bar
        // title (2 nodes) — proving the title uses the friendly role label, not the raw "Sent Mail".
        composeTestRule.waitUntil(TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(string(R.string.folder_sent)).fetchSemanticsNodes().size == 2
        }
        composeTestRule.onNodeWithText("Sent Mail").assertDoesNotExist()
    }

    // --- Harness ---------------------------------------------------------------------------------

    private fun waitForText(text: String) = composeTestRule.waitUntil(TIMEOUT_MS) {
        composeTestRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    /**
     * Builds the real [MailboxViewModel] over a relaxed [MailRepository]/[AccountRepository]/[Syncer]
     * and hosts [MailboxScreen] on it. Every mailbox mode is a single paged flow (issues #124, #214);
     * each repository pager is mirrored as one static [PagingData.from] page over [messages] (browse
     * pagers keep folder-synced `inInbox` rows), exactly as [MailboxViewModelTest] does — no real
     * Room/Paging source. [pagedOverride]/[searchOverride] swap in a pre-baked [PagingData] flow (with
     * explicit [LoadStates]) to drive the empty/loading/no-results gates deterministically.
     */
    private fun setContent(
        accounts: List<Account> = listOf(account),
        messages: List<Message> = emptyList(),
        folders: Map<String, List<Folder>> = emptyMap(),
        draftCount: Int = 0,
        outboxCount: Int = 0,
        unreadCounts: List<UnreadCount> = emptyList(),
        pagedOverride: Flow<PagingData<Message>>? = null,
        searchOverride: Flow<PagingData<Message>>? = null,
        repo: MailRepository = mockk(relaxed = true),
        syncer: Syncer = mockk(relaxed = true),
        onOpenMessage: (String) -> Unit = {},
        onOpenDrafts: () -> Unit = {},
        onOpenOutbox: () -> Unit = {},
        onAddAccount: () -> Unit = {},
    ): MailboxViewModel {
        configurePagers(repo, messages, pagedOverride, searchOverride)
        every { repo.observeDrafts() } returns MutableStateFlow(List(draftCount) { draft("d$it") })
        every { repo.observeOutbox() } returns MutableStateFlow(List(outboxCount) { outbox("o$it") })
        every { repo.observeUnreadCounts() } returns MutableStateFlow(unreadCounts)
        accounts.forEach { acct ->
            every { repo.observeFolders(acct.id) } returns MutableStateFlow(folders[acct.id] ?: emptyList())
        }
        val accountRepository = mockk<AccountRepository>(relaxed = true)
        every { accountRepository.observeAccounts() } returns MutableStateFlow(accounts)
        val viewModel = MailboxViewModel(repo, accountRepository, syncer, SavedStateHandle())
        composeTestRule.setContent {
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                MailboxScreen(
                    onOpenMessage = onOpenMessage,
                    onCompose = {},
                    onOpenDrafts = onOpenDrafts,
                    onOpenOutbox = onOpenOutbox,
                    onAddAccount = onAddAccount,
                    onOpenCompose = {},
                    onSelectTab = {},
                    viewModel = viewModel,
                )
            }
        }
        return viewModel
    }

    /**
     * Stubs the four repository pagers. Each browse pager mirrors its DAO projection as one static
     * [PagingData.from] page over [messages] (folder-synced `inInbox` rows); each search pager matches
     * the same columns the DAO's LIKE query does. [pagedOverride]/[searchOverride] instead return a
     * pre-baked [PagingData] flow (with explicit [LoadStates]) for the empty/loading/no-results gates.
     */
    private fun configurePagers(
        repo: MailRepository,
        messages: List<Message>,
        pagedOverride: Flow<PagingData<Message>>?,
        searchOverride: Flow<PagingData<Message>>?,
    ) {
        if (pagedOverride != null) {
            every { repo.pagedUnifiedFolderMessages(any()) } returns pagedOverride
            every { repo.pagedFolderMessages(any(), any()) } returns pagedOverride
        } else {
            every { repo.pagedUnifiedFolderMessages(any()) } answers {
                val folder = firstArg<String>()
                flowOf(PagingData.from(messages.filter { it.folder == folder && it.inInbox }))
            }
            every { repo.pagedFolderMessages(any(), any()) } answers {
                val accountId = firstArg<String>()
                val folder = secondArg<String>()
                flowOf(
                    PagingData.from(
                        messages.filter { it.accountId == accountId && it.folder == folder && it.inInbox },
                    ),
                )
            }
        }
        if (searchOverride != null) {
            every { repo.pagedUnifiedSearchMessages(any(), any()) } returns searchOverride
            every { repo.pagedFolderSearchMessages(any(), any(), any()) } returns searchOverride
        } else {
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
        }
    }

    private fun draft(id: String) = Draft(
        id = id,
        accountId = "imap:a",
        to = "",
        cc = "",
        subject = "",
        body = "",
        updatedAt = 1_000L,
    )

    private fun outbox(id: String) =
        OutboxMessage(id = id, to = "", subject = "", body = "", createdAt = 1_000L, lastError = null)

    private fun settledEmpty(): Flow<PagingData<Message>> = flowOf(
        PagingData.from(
            emptyList<Message>(),
            LoadStates(
                refresh = LoadState.NotLoading(endOfPaginationReached = false),
                prepend = LoadState.NotLoading(endOfPaginationReached = true),
                append = LoadState.NotLoading(endOfPaginationReached = true),
            ),
        ),
    )

    private fun loadingEmpty(): Flow<PagingData<Message>> = flowOf(
        PagingData.from(
            emptyList<Message>(),
            LoadStates(
                refresh = LoadState.Loading,
                prepend = LoadState.NotLoading(endOfPaginationReached = false),
                append = LoadState.NotLoading(endOfPaginationReached = false),
            ),
        ),
    )

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}

/** Mirrors the paged-search DAO queries' columns so the stubbed search pagers filter like production. */
private fun Message.matchesSearch(query: String): Boolean = sender.contains(query, ignoreCase = true) ||
    senderEmail.contains(query, ignoreCase = true) ||
    subject.contains(query, ignoreCase = true) ||
    snippet.contains(query, ignoreCase = true)
