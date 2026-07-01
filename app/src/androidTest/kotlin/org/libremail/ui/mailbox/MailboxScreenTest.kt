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
import androidx.test.ext.junit.runners.AndroidJUnit4
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

    private fun message(uid: String, subject: String, bodyFetched: Boolean = false) = Message(
        id = "imap:a:INBOX:$uid",
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
        folder = "INBOX",
        inInbox = true,
        bodyFetched = bodyFetched,
    )

    private fun setContent(repo: FakeMailRepository) {
        val viewModel = MailboxViewModel(
            repo,
            FakeAccountRepository(accounts = listOf(account)),
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

        composeTestRule.onNodeWithText(string(R.string.action_archive)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.action_reply)).assertDoesNotExist()
        composeTestRule.onNodeWithText(string(R.string.action_forward)).assertDoesNotExist()
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
    fun offlineIndicator_showsForCachedMessages() {
        setContent(FakeMailRepository(messages = listOf(message("1", "Cached", bodyFetched = true))))
        waitForText("Cached")

        composeTestRule.onNodeWithContentDescription(string(R.string.message_available_offline)).assertIsDisplayed()
    }
}
