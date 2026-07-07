// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.mailbox

import android.content.Context
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric JVM port of the instrumented [FolderDrawerTest] (batch 8/9 of umbrella #373): drives the
 * callback-driven [FolderDrawer] on the JVM via the v2 `createComposeRule()` — no emulator — so its
 * render + interaction code counts toward JaCoCo's JVM-testable surface. Covers folder rendering with
 * friendly role names, the duplicate-name provider disambiguation (and the account-switch gap that
 * keeps the stale list's own suffix), tapping a folder, the multi-account switcher + "All Inboxes"
 * entry, the account-switcher dropdown, and the unread badge (including the "99+" overflow cap). The
 * instrumented [FolderDrawerTest] stays as the on-device E2E. See
 * [org.libremail.ui.onboarding.AddAnotherAccountScreenJvmTest] for the pattern.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class FolderDrawerJvmTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun string(resId: Int) = context.getString(resId)

    private fun unreadDescription(count: Int) =
        context.resources.getQuantityString(R.plurals.folder_unread_count_description, count, count)

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
                // Give ARCHIVE a server name that differs from its friendly label so the assertion
                // below actually discriminates a role-to-label regression (displayName != friendly).
                folder("imap:a", "[Gmail]/All Mail", "All Mail", FolderRole.ARCHIVE),
                folder("imap:a", "Receipts", "Receipts", FolderRole.NORMAL),
            ),
        )

        composeTestRule.onNodeWithText(string(R.string.folder_inbox)).assertIsDisplayed()
        // Standard folders use the friendly role name, not the raw server name — verified for both
        // Sent ("Sent Mail" -> "Sent") and Archive ("All Mail" -> "Archive").
        composeTestRule.onNodeWithText(string(R.string.folder_sent)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Sent Mail").assertDoesNotExist()
        composeTestRule.onNodeWithText(string(R.string.folder_archive)).assertIsDisplayed()
        composeTestRule.onNodeWithText("All Mail").assertDoesNotExist()
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

    @Test
    fun accountSwitcher_expandsDropdown_andSelectsAnotherAccount() {
        var switchedTo: String? = null
        setContent(
            accounts = listOf(alice, bob),
            drawerAccount = alice,
            folders = listOf(folder("imap:a", "INBOX", "INBOX", FolderRole.INBOX)),
            onSelectDrawerAccount = { switchedTo = it },
        )

        // Only the switcher shows the current account initially; opening it lists every account.
        composeTestRule.onNodeWithText("alice@example.org").performClick()
        composeTestRule.onNodeWithText("bob@example.org").performClick()

        assertEquals("imap:b", switchedTo)
    }

    @Test
    fun folderWithUnreadMail_showsCountBadge_andReadFolderShowsNone() {
        setContent(
            accounts = listOf(alice),
            drawerAccount = alice,
            folders = listOf(
                folder("imap:a", "INBOX", "INBOX", FolderRole.INBOX),
                folder("imap:a", "Archive", "Archive", FolderRole.ARCHIVE),
            ),
            folderUnreadCounts = mapOf("INBOX" to 3),
        )

        // The inbox badge announces its exact count for screen readers.
        composeTestRule.onNodeWithContentDescription(unreadDescription(3)).assertIsDisplayed()
        // Archive has no unread mail, so no badge is rendered for it.
        composeTestRule.onNodeWithContentDescription(unreadDescription(1)).assertDoesNotExist()
    }

    @Test
    fun unreadCountOverTheCap_rendersOverflowBadge() {
        setContent(
            accounts = listOf(alice),
            drawerAccount = alice,
            folders = listOf(folder("imap:a", "INBOX", "INBOX", FolderRole.INBOX)),
            folderUnreadCounts = mapOf("INBOX" to 150),
        )

        // Over the 99 cap the glyph collapses to "99+" while the semantics still announce the exact
        // count; the visible "99+" is cleared from the a11y tree (clearAndSetSemantics), so it is only
        // reachable by the content description.
        composeTestRule.onNodeWithContentDescription(unreadDescription(150)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.folder_unread_overflow)).assertDoesNotExist()
    }

    private fun setContent(
        accounts: List<Account>,
        drawerAccount: Account?,
        folders: List<Folder>,
        folderUnreadCounts: Map<String, Int> = emptyMap(),
        accountsWithUnread: Set<String> = emptySet(),
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
                        folderUnreadCounts = folderUnreadCounts,
                        accountsWithUnread = accountsWithUnread,
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
