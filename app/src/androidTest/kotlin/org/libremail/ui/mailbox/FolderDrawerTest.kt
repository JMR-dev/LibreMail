// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.mailbox

import androidx.activity.ComponentActivity
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.R
import org.libremail.domain.model.Account
import org.libremail.domain.model.AuthType
import org.libremail.domain.model.Folder
import org.libremail.domain.model.FolderRole
import org.libremail.domain.model.MailSecurity
import org.libremail.domain.model.ServerConfig
import org.libremail.ui.theme.LibreMailTheme

/** UI test for the navigation drawer: folder rendering, friendly names, the account switcher, and taps. */
@RunWith(AndroidJUnit4::class)
class FolderDrawerTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun string(resId: Int) = composeTestRule.activity.getString(resId)

    private val alice = account("imap:a", "alice@example.org")
    private val bob = account("imap:b", "bob@example.org")

    @Test
    fun singleAccount_rendersStandardFoldersWithFriendlyNames() {
        setContent(
            accounts = listOf(alice),
            drawerAccount = alice,
            folders = listOf(
                folder("imap:a", "INBOX", "INBOX", FolderRole.INBOX),
                folder("imap:a", "[Gmail]/Sent Mail", "Sent Mail", FolderRole.SENT),
                folder("imap:a", "Archive", "Archive", FolderRole.ARCHIVE),
                folder("imap:a", "Receipts", "Receipts", FolderRole.NORMAL),
            ),
        )

        composeTestRule.onNodeWithText(string(R.string.folder_inbox)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.folder_archive)).assertIsDisplayed()
        // Standard folders use the friendly role name ("Sent"), not the raw server name ("Sent Mail").
        composeTestRule.onNodeWithText(string(R.string.folder_sent)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Sent Mail").assertDoesNotExist()
        // Normal folders keep their server name.
        composeTestRule.onNodeWithText("Receipts").assertIsDisplayed()
        // A single account shows no account switcher / "All Inboxes" entry.
        composeTestRule.onNodeWithText(string(R.string.folder_all_inboxes)).assertDoesNotExist()
    }

    @Test
    fun duplicateFolderNames_areDisambiguatedWithProviderSuffix() {
        val gmail = account("imap:g", "user@gmail.com").copy(
            imap = ServerConfig("imap.gmail.com", 993, MailSecurity.SSL_TLS),
        )
        setContent(
            accounts = listOf(gmail),
            drawerAccount = gmail,
            folders = listOf(
                folder("imap:g", "INBOX", "INBOX", FolderRole.INBOX),
                // Gmail's built-in Drafts (server special-use) alongside a same-named user folder.
                folder("imap:g", "[Gmail]/Drafts", "Drafts", FolderRole.DRAFTS, specialUse = true),
                folder("imap:g", "Drafts", "Drafts", FolderRole.DRAFTS),
            ),
        )

        // The provider's built-in folder is suffixed; the user folder keeps the plain name.
        composeTestRule.onNodeWithText("Drafts - Gmail").assertIsDisplayed()
        composeTestRule.onNodeWithText("Drafts").assertIsDisplayed()
    }

    @Test
    fun accountSwitchGap_staleFolderListKeepsItsOwnProviderSuffix() {
        val gmail = account("imap:g", "user@gmail.com").copy(
            imap = ServerConfig("imap.gmail.com", 993, MailSecurity.SSL_TLS),
        )
        val outlook = account("imap:o", "user@outlook.com").copy(
            authType = AuthType.OAUTH_OUTLOOK,
            imap = ServerConfig("outlook.office365.com", 993, MailSecurity.SSL_TLS),
        )
        // The transient frame from issue #61: the drawer account has already switched to Outlook,
        // but the folder list still holds the Gmail account's folders until its query emits.
        setContent(
            accounts = listOf(gmail, outlook),
            drawerAccount = outlook,
            folders = listOf(
                folder("imap:g", "INBOX", "INBOX", FolderRole.INBOX),
                folder("imap:g", "[Gmail]/Drafts", "Drafts", FolderRole.DRAFTS, specialUse = true),
                folder("imap:g", "Drafts", "Drafts", FolderRole.DRAFTS),
            ),
        )

        // The de-dup suffix derives from the folders' own account — never the incoming account.
        composeTestRule.onNodeWithText("Drafts - Gmail").assertIsDisplayed()
        composeTestRule.onNodeWithText("Drafts - Outlook").assertDoesNotExist()
    }

    @Test
    fun tappingAFolder_reportsItsAccountAndFullName() {
        var picked: Pair<String, String>? = null
        setContent(
            accounts = listOf(alice),
            drawerAccount = alice,
            folders = listOf(
                folder("imap:a", "INBOX", "INBOX", FolderRole.INBOX),
                folder("imap:a", "Archive", "Archive", FolderRole.ARCHIVE),
            ),
            onSelectFolder = { accountId, fullName -> picked = accountId to fullName },
        )

        composeTestRule.onNodeWithText(string(R.string.folder_archive)).performClick()

        assertEquals("imap:a" to "Archive", picked)
    }

    @Test
    fun multipleAccounts_showSwitcherAndAllInboxesEntry() {
        var unifiedTapped = false
        setContent(
            accounts = listOf(alice, bob),
            drawerAccount = alice,
            folders = listOf(folder("imap:a", "INBOX", "INBOX", FolderRole.INBOX)),
            onSelectUnifiedInbox = { unifiedTapped = true },
        )

        // The switcher shows the active drawer account, and the unified entry is available.
        composeTestRule.onNodeWithText("alice@example.org").assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.folder_all_inboxes)).assertIsDisplayed()

        composeTestRule.onNodeWithText(string(R.string.folder_all_inboxes)).performClick()
        assertTrue(unifiedTapped)
    }

    private fun setContent(
        accounts: List<Account>,
        drawerAccount: Account?,
        folders: List<Folder>,
        selectedAccountId: String? = null,
        selectedFolder: String = "INBOX",
        onSelectUnifiedInbox: () -> Unit = {},
        onSelectFolder: (String, String) -> Unit = { _, _ -> },
        onSelectDrawerAccount: (String) -> Unit = {},
    ) {
        composeTestRule.setContent {
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                ModalDrawerSheet {
                    FolderDrawer(
                        accounts = accounts,
                        drawerAccount = drawerAccount,
                        folders = folders,
                        selectedAccountId = selectedAccountId,
                        selectedFolder = selectedFolder,
                        onSelectUnifiedInbox = onSelectUnifiedInbox,
                        onSelectFolder = onSelectFolder,
                        onSelectDrawerAccount = onSelectDrawerAccount,
                    )
                }
            }
        }
    }

    private fun account(id: String, email: String) = Account(
        id = id,
        email = email,
        displayName = email,
        authType = AuthType.PASSWORD_IMAP,
        imap = ServerConfig("imap.example.org", 993, MailSecurity.SSL_TLS),
        smtp = ServerConfig("smtp.example.org", 465, MailSecurity.SSL_TLS),
    )

    private fun folder(
        accountId: String,
        fullName: String,
        displayName: String,
        role: FolderRole,
        specialUse: Boolean = false,
    ) = Folder(accountId, fullName, displayName, role, selectable = true, specialUse = specialUse)
}
