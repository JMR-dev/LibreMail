// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.mailbox

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.SavedStateHandle
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.R
import org.libremail.domain.model.Account
import org.libremail.domain.model.AuthType
import org.libremail.domain.model.Folder
import org.libremail.domain.model.FolderRole
import org.libremail.domain.model.MailSecurity
import org.libremail.domain.model.Message
import org.libremail.domain.model.ServerConfig
import org.libremail.ui.FakeAccountRepository
import org.libremail.ui.FakeMailRepository
import org.libremail.ui.FakeMailSyncer
import org.libremail.ui.theme.LibreMailTheme

/**
 * End-to-end UI test for the mailbox's long-press contextual action bar. Drives the real
 * [MailboxScreen] + [MailboxViewModel] backed by in-memory fakes.
 */
@RunWith(AndroidJUnit4::class)
class MailboxScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun string(resId: Int) = composeTestRule.activity.getString(resId)
    private fun count(n: Int) = composeTestRule.activity.getString(R.string.cab_selected_count, n)

    private val account = Account(
        id = "imap:a",
        email = "a@example.org",
        displayName = "A",
        authType = AuthType.PASSWORD_IMAP,
        imap = ServerConfig("imap.example.org", 993, MailSecurity.SSL_TLS),
        smtp = ServerConfig("smtp.example.org", 465, MailSecurity.SSL_TLS),
    )

    private val gmailAccount = account.copy(
        email = "a@gmail.com",
        imap = ServerConfig("imap.gmail.com", 993, MailSecurity.SSL_TLS),
    )

    /** A Gmail-style tree where the built-in Drafts collides with a same-named user folder. */
    private val duplicateDraftsFolders = listOf(
        Folder("imap:a", "INBOX", "INBOX", FolderRole.INBOX, selectable = true),
        Folder("imap:a", "[Gmail]/Drafts", "Drafts", FolderRole.DRAFTS, selectable = true, specialUse = true),
        Folder("imap:a", "Drafts", "Drafts", FolderRole.DRAFTS, selectable = true),
    )

    private fun message(uid: String, subject: String, bodyFetched: Boolean = false, folder: String = "INBOX") = Message(
        id = "imap:a:$folder:$uid",
        accountId = "imap:a",
        sender = "Sender $uid",
        senderEmail = "s$uid@example.org",
        subject = subject,
        snippet = "",
        body = "",
        isHtml = false,
        timestampMillis = 1_000L,
        isRead = true,
        isStarred = false,
        folder = folder,
        inInbox = true,
        bodyFetched = bodyFetched,
    )

    private fun setContent(repo: FakeMailRepository, activeAccount: Account = account): MailboxViewModel {
        val viewModel = MailboxViewModel(
            repo,
            FakeAccountRepository(accounts = listOf(activeAccount)),
            FakeMailSyncer(),
            SavedStateHandle(),
        )
        composeTestRule.setContent {
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                MailboxScreen(
                    onOpenMessage = {},
                    onCompose = {},
                    onOpenDrafts = {},
                    onOpenOutbox = {},
                    onAddAccount = {},
                    onOpenCompose = {},
                    onSelectTab = {},
                    viewModel = viewModel,
                )
            }
        }
        return viewModel
    }

    private fun waitForText(text: String) = composeTestRule.waitUntil(5_000) {
        composeTestRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun longPress_entersSelection_andCountTracksTaps() {
        setContent(FakeMailRepository(messages = listOf(message("1", "First"), message("2", "Second"))))
        waitForText("First")

        composeTestRule.onNodeWithText("First").performTouchInput { longClick() }
        composeTestRule.onNodeWithText(count(1)).assertIsDisplayed()

        composeTestRule.onNodeWithText("Second").performClick()
        composeTestRule.onNodeWithText(count(2)).assertIsDisplayed()
    }

    @Test
    fun overflow_showsReplyActions_forSingleSelection() {
        setContent(FakeMailRepository(messages = listOf(message("1", "First"))))
        waitForText("First")

        composeTestRule.onNodeWithText("First").performTouchInput { longClick() }
        composeTestRule.onNodeWithContentDescription(string(R.string.action_more)).performClick()

        composeTestRule.onNodeWithText(string(R.string.action_reply)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.action_reply_all)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.action_forward)).assertIsDisplayed()
    }

    @Test
    fun overflow_hidesReplyActions_forMultiSelection() {
        setContent(FakeMailRepository(messages = listOf(message("1", "First"), message("2", "Second"))))
        waitForText("First")

        composeTestRule.onNodeWithText("First").performTouchInput { longClick() }
        composeTestRule.onNodeWithText("Second").performClick()
        composeTestRule.onNodeWithContentDescription(string(R.string.action_more)).performClick()

        composeTestRule.onNodeWithText(string(R.string.action_select_all)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.action_reply)).assertDoesNotExist()
        composeTestRule.onNodeWithText(string(R.string.action_forward)).assertDoesNotExist()
    }

    @Test
    fun archiveIcon_isDirect_andArchivesTheSelection() {
        val repo = FakeMailRepository(messages = listOf(message("1", "First"), message("2", "Second")))
        setContent(repo)
        waitForText("First")

        composeTestRule.onNodeWithText("First").performTouchInput { longClick() }
        composeTestRule.onNodeWithText("Second").performClick()
        // A direct icon button — no trip through the overflow menu.
        composeTestRule.onNodeWithContentDescription(string(R.string.action_archive)).performClick()

        composeTestRule.waitUntil(5_000) { repo.archivedIds.isNotEmpty() }
        assertEquals(setOf("imap:a:INBOX:1", "imap:a:INBOX:2"), repo.archivedIds.first().toSet())
    }

    @Test
    fun spamIcon_isDirect_andConfirmsBeforeReporting() {
        val repo = FakeMailRepository(messages = listOf(message("1", "First")))
        setContent(repo)
        waitForText("First")

        composeTestRule.onNodeWithText("First").performTouchInput { longClick() }
        composeTestRule.onNodeWithContentDescription(string(R.string.action_spam)).performClick()
        composeTestRule.onNodeWithText(string(R.string.confirm_spam_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.action_move)).performClick()

        composeTestRule.waitUntil(5_000) { repo.spammedIds.isNotEmpty() }
        assertEquals(listOf("imap:a:INBOX:1"), repo.spammedIds.first())
    }

    @Test
    fun archiveIcon_hides_whileViewingTheArchiveFolder() {
        val repo = FakeMailRepository(
            messages = listOf(message("1", "Old news", folder = "Archive")),
            folders = listOf(Folder("imap:a", "Archive", "Archive", FolderRole.ARCHIVE, selectable = true)),
        )
        val viewModel = setContent(repo)
        viewModel.selectFolder("imap:a", "Archive")
        waitForText("Old news")

        composeTestRule.onNodeWithText("Old news").performTouchInput { longClick() }

        composeTestRule.onNodeWithContentDescription(string(R.string.action_archive)).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(string(R.string.action_spam)).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(string(R.string.action_delete)).assertIsDisplayed()
    }

    @Test
    fun delete_confirmsMoveToTrash_thenTrashesViaRepository() {
        val repo = FakeMailRepository(messages = listOf(message("1", "First")))
        setContent(repo)
        waitForText("First")

        composeTestRule.onNodeWithText("First").performTouchInput { longClick() }
        composeTestRule.onNodeWithContentDescription(string(R.string.action_delete)).performClick()
        composeTestRule.onNodeWithText(string(R.string.confirm_trash_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.action_move)).performClick()

        composeTestRule.waitUntil(5_000) { repo.trashedIds.isNotEmpty() }
        assertEquals(listOf("imap:a:INBOX:1"), repo.trashedIds.first())
    }

    @Test
    fun move_picker_movesSelectionToTheChosenFolder() {
        val repo = FakeMailRepository(
            messages = listOf(message("1", "First")),
            folders = listOf(
                Folder("imap:a", "INBOX", "INBOX", FolderRole.INBOX, selectable = true),
                Folder("imap:a", "Receipts", "Receipts", FolderRole.NORMAL, selectable = true),
            ),
        )
        setContent(repo)
        waitForText("First")

        composeTestRule.onNodeWithText("First").performTouchInput { longClick() }
        composeTestRule.onNodeWithContentDescription(string(R.string.action_more)).performClick()
        composeTestRule.onNodeWithText(string(R.string.action_move)).performClick()
        // The off-screen navigation drawer also lists "Receipts", so scope the tap to the move dialog.
        composeTestRule.onNode(hasText("Receipts") and hasAnyAncestor(isDialog())).performClick()

        composeTestRule.waitUntil(5_000) { repo.movedToFolder.isNotEmpty() }
        assertEquals("Receipts", repo.movedToFolder.first().second)
    }

    @Test
    fun movePicker_disambiguatesDuplicateNames_andMovesToTheChosenFolder() {
        val repo = FakeMailRepository(
            messages = listOf(message("1", "First")),
            folders = duplicateDraftsFolders,
        )
        setContent(repo, activeAccount = gmailAccount)
        waitForText("First")

        composeTestRule.onNodeWithText("First").performTouchInput { longClick() }
        composeTestRule.onNodeWithContentDescription(string(R.string.action_more)).performClick()
        composeTestRule.onNodeWithText(string(R.string.action_move)).performClick()

        // Issue #59: the two same-named Drafts folders are told apart in the picker, and choosing
        // the suffixed row files into the provider's built-in folder — not the user folder.
        composeTestRule.onNode(hasText("Drafts") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        composeTestRule.onNode(hasText("Drafts - Gmail") and hasAnyAncestor(isDialog())).performClick()

        composeTestRule.waitUntil(5_000) { repo.movedToFolder.isNotEmpty() }
        assertEquals("[Gmail]/Drafts", repo.movedToFolder.first().second)
    }

    @Test
    fun appBarTitle_keepsTheDisambiguatedFolderLabel() {
        val repo = FakeMailRepository(
            messages = listOf(message("1", "First")),
            folders = duplicateDraftsFolders,
        )
        setContent(repo, activeAccount = gmailAccount)
        waitForText("First")

        composeTestRule.onNodeWithContentDescription(string(R.string.drawer_open)).performClick()
        composeTestRule.onNodeWithText("Drafts - Gmail").assertIsDisplayed()
        composeTestRule.onNodeWithText("Drafts - Gmail").performClick()

        // Issue #59: the app-bar title keeps the drawer's de-duplicated label instead of collapsing
        // to an ambiguous "Drafts". Once selected, the label renders twice — the app-bar title plus
        // the (composed but closed) drawer's entry.
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("Drafts - Gmail").fetchSemanticsNodes().size == 2
        }
    }

    @Test
    fun offlineIndicator_showsForCachedMessages() {
        setContent(FakeMailRepository(messages = listOf(message("1", "Cached", bodyFetched = true))))
        waitForText("Cached")

        composeTestRule.onNodeWithContentDescription(string(R.string.message_available_offline)).assertIsDisplayed()
    }

    // Issue #219: on return from the reader the inbox's LazyPagingItems is cold — it presents
    // itemCount == 0 with refresh == Loading before the window repopulates. The empty-state gate must
    // hold "No messages yet" back through that window, so the empty state never flashes.
    @Test
    fun emptyState_isHidden_whileTheInboxPagerIsStillLoading() {
        val loadingEmpty = flowOf(
            PagingData.from(
                emptyList<Message>(),
                LoadStates(
                    refresh = LoadState.Loading,
                    prepend = LoadState.NotLoading(endOfPaginationReached = false),
                    append = LoadState.NotLoading(endOfPaginationReached = false),
                ),
            ),
        )
        setContent(FakeMailRepository(pagedOverride = loadingEmpty))

        // The screen has composed (its compose FAB is present in the tree) rather than having crashed
        // or rendered blank — that's the sanity check here, not the FAB's on-screen visibility. This
        // scenario has no other loading affordance to point at instead: isSyncingFolder (the spinner
        // gate below) only applies to a per-folder background sync started by selectFolder(), which
        // this test never calls, so nothing else is guaranteed visible while refresh == Loading.
        // assertExists() rather than assertIsDisplayed() for that reason — the FAB isn't under test here.
        composeTestRule.onNodeWithText(string(R.string.action_compose)).assertExists()
        composeTestRule.onNodeWithText(string(R.string.mailbox_empty)).assertDoesNotExist()
    }

    // The flip side of the gate: once the pager settles (refresh done, no further page) with no rows,
    // the inbox really is empty and "No messages yet" must show.
    @Test
    fun emptyState_isShown_onceTheInboxPagerSettlesEmpty() {
        val settledEmpty = flowOf(
            PagingData.from(
                emptyList<Message>(),
                LoadStates(
                    refresh = LoadState.NotLoading(endOfPaginationReached = false),
                    prepend = LoadState.NotLoading(endOfPaginationReached = true),
                    append = LoadState.NotLoading(endOfPaginationReached = true),
                ),
            ),
        )
        setContent(FakeMailRepository(pagedOverride = settledEmpty))

        waitForText(string(R.string.mailbox_empty))
        composeTestRule.onNodeWithText(string(R.string.mailbox_empty)).assertIsDisplayed()
    }
}
