// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.mailbox

import org.junit.Test
import org.libremail.domain.model.Account
import org.libremail.domain.model.AuthType
import org.libremail.domain.model.Folder
import org.libremail.domain.model.FolderRole
import org.libremail.domain.model.MailSecurity
import org.libremail.domain.model.ServerConfig
import kotlin.test.assertEquals

class FolderLabelsTest {

    private fun folder(
        fullName: String,
        displayName: String = fullName.substringAfterLast('/'),
        role: FolderRole = FolderRole.NORMAL,
        specialUse: Boolean = false,
        accountId: String = "acct",
    ) = Folder(accountId, fullName, displayName, role, selectable = true, specialUse = specialUse)

    private fun account(
        email: String,
        imapHost: String,
        authType: AuthType = AuthType.PASSWORD_IMAP,
        id: String = "acct",
    ) = Account(
        id = id,
        email = email,
        displayName = email,
        authType = authType,
        imap = ServerConfig(imapHost, 993, MailSecurity.SSL_TLS),
        smtp = ServerConfig("smtp", 587, MailSecurity.STARTTLS),
    )

    /**
     * Builds base labels from an independent, hand-written [friendly] map, NOT by calling the
     * production label source [org.libremail.ui.mailbox.folderDisplayLabel] — that is `@Composable`
     * (it reads `stringResource`) and can't run in a JVM unit test. So this is a stand-in literal
     * fixture that only mirrors the `folder_*` string values by hand; it deliberately pins the
     * de-duplication logic in [resolveDrawerLabels], not the role-to-wording mapping. A change to the
     * `folder_*` strings (or a new [folderDisplayLabel] branch) is out of scope here and won't be
     * caught by this suite — FolderDrawerTest (androidTest, real resources) guards that wiring.
     */
    private fun baseLabelsOf(folders: List<Folder>, friendly: Map<FolderRole, String>) =
        folders.associate { it.fullName to (friendly[it.role] ?: it.displayName) }

    // Hand-copied stand-ins for the current folder_* string values, not read from resources. See the
    // note on baseLabelsOf: these pin the de-dup behavior, not the actual role-to-string wording.
    private val friendlyNames = mapOf(
        FolderRole.INBOX to "Inbox",
        FolderRole.SENT to "Sent",
        FolderRole.DRAFTS to "Drafts",
        FolderRole.ARCHIVE to "Archive",
        FolderRole.SPAM to "Spam",
        FolderRole.TRASH to "Trash",
    )

    private fun resolve(folders: List<Folder>, provider: String) =
        resolveDrawerLabels(folders, baseLabelsOf(folders, friendlyNames), provider)

    @Test
    fun `provider special folder gets the suffix and the user folder keeps its name`() {
        val folders = listOf(
            folder("[Gmail]/Drafts", "Drafts", FolderRole.DRAFTS, specialUse = true),
            folder("Drafts", "Drafts", FolderRole.DRAFTS),
        )
        val labels = resolve(folders, "Gmail")
        assertEquals("Drafts - Gmail", labels["[Gmail]/Drafts"])
        assertEquals("Drafts", labels["Drafts"])
    }

    @Test
    fun `all three reported Gmail duplicates are disambiguated`() {
        val folders = listOf(
            folder("[Gmail]/Drafts", "Drafts", FolderRole.DRAFTS, specialUse = true),
            folder("Drafts", "Drafts", FolderRole.DRAFTS),
            folder("[Gmail]/All Mail", "All Mail", FolderRole.ARCHIVE, specialUse = true),
            folder("Archive", "Archive", FolderRole.ARCHIVE),
            folder("[Gmail]/Spam", "Spam", FolderRole.SPAM, specialUse = true),
            folder("Spam", "Spam", FolderRole.SPAM),
        )
        val labels = resolve(folders, "Gmail")
        assertEquals("Drafts - Gmail", labels["[Gmail]/Drafts"])
        assertEquals("Drafts", labels["Drafts"])
        assertEquals("Archive - Gmail", labels["[Gmail]/All Mail"])
        assertEquals("Archive", labels["Archive"])
        assertEquals("Spam - Gmail", labels["[Gmail]/Spam"])
        assertEquals("Spam", labels["Spam"])
    }

    @Test
    fun `two nested user folders with the same leaf get their parent location`() {
        val folders = listOf(
            folder("Work/Reports"),
            folder("Personal/Reports"),
        )
        val labels = resolve(folders, "example.org")
        assertEquals("Reports (Work)", labels["Work/Reports"])
        assertEquals("Reports (Personal)", labels["Personal/Reports"])
    }

    @Test
    fun `a top-level user folder keeps its name against a nested sibling`() {
        val folders = listOf(
            folder("Reports"),
            folder("Work/Reports"),
        )
        val labels = resolve(folders, "example.org")
        assertEquals("Reports", labels["Reports"])
        assertEquals("Reports (Work)", labels["Work/Reports"])
    }

    @Test
    fun `unique labels are left untouched`() {
        val folders = listOf(
            folder("[Gmail]/Drafts", "Drafts", FolderRole.DRAFTS, specialUse = true),
            folder("Receipts"),
        )
        val labels = resolve(folders, "Gmail")
        assertEquals("Drafts", labels["[Gmail]/Drafts"])
        assertEquals("Receipts", labels["Receipts"])
    }

    @Test
    fun `two special folders for one role stay distinct via the full-path safety net`() {
        val folders = listOf(
            folder("[Gmail]/All Mail", "All Mail", FolderRole.ARCHIVE, specialUse = true),
            folder("Archives", "Archives", FolderRole.ARCHIVE, specialUse = true),
        )
        val labels = resolve(folders, "Gmail")
        assertEquals(2, labels.values.toSet().size, "every drawer entry must be unique")
    }

    // Issue #60: on a server without SPECIAL-USE (e.g. GreenMail) top-level "Sent" and "Sent Items"
    // both classify as SENT via the name fallback and share the friendly "Sent" base label. The tie
    // must break on the display name, not net out as a self-referential "Sent [Sent]".
    @Test
    fun `top-level folders sharing a role tie-break on their names, not a self-referential path`() {
        val folders = listOf(
            folder("Sent", "Sent", FolderRole.SENT),
            folder("Sent Items", "Sent Items", FolderRole.SENT),
        )
        val labels = resolve(folders, "example.org")
        assertEquals("Sent", labels["Sent"])
        assertEquals("Sent Items", labels["Sent Items"])
    }

    @Test
    fun `same-role top-level folders where none matches the friendly name keep their own names`() {
        val folders = listOf(
            folder("Sent Mail", "Sent Mail", FolderRole.SENT),
            folder("Sent Items", "Sent Items", FolderRole.SENT),
        )
        val labels = resolve(folders, "example.org")
        assertEquals("Sent Mail", labels["Sent Mail"])
        assertEquals("Sent Items", labels["Sent Items"])
    }

    @Test
    fun `provider special, canonical, and synonym folders for one role all stay apart`() {
        val folders = listOf(
            folder("[Gmail]/Sent Mail", "Sent Mail", FolderRole.SENT, specialUse = true),
            folder("Sent", "Sent", FolderRole.SENT),
            folder("Sent Items", "Sent Items", FolderRole.SENT),
        )
        val labels = resolve(folders, "Gmail")
        assertEquals("Sent - Gmail", labels["[Gmail]/Sent Mail"])
        assertEquals("Sent", labels["Sent"])
        assertEquals("Sent Items", labels["Sent Items"])
    }

    // Issue #61: while switching accounts the drawer's account updates before its folder list, so
    // the provider suffix must derive from the rendered folders' own account — never from the newer
    // drawer selection.
    @Test
    fun `providerLabelFor derives the suffix from the folder list's owner account`() {
        val gmail = account("user@gmail.com", "imap.gmail.com", id = "acct-gmail")
        val outlook =
            account("user@outlook.com", "outlook.office365.com", AuthType.OAUTH_OUTLOOK, id = "acct-outlook")
        // The drawer account has already switched to Outlook, but these stale folders are Gmail's.
        val staleGmailFolders = listOf(
            folder("[Gmail]/Drafts", "Drafts", FolderRole.DRAFTS, specialUse = true, accountId = "acct-gmail"),
            folder("Drafts", "Drafts", FolderRole.DRAFTS, accountId = "acct-gmail"),
        )

        val provider = providerLabelFor(staleGmailFolders, listOf(gmail, outlook))

        assertEquals("Gmail", provider)
        val labels =
            resolveDrawerLabels(staleGmailFolders, baseLabelsOf(staleGmailFolders, friendlyNames), provider)
        assertEquals("Drafts - Gmail", labels["[Gmail]/Drafts"])
        assertEquals("Drafts", labels["Drafts"])
    }

    @Test
    fun `providerLabelFor is empty for an empty or orphaned folder list`() {
        val outlook =
            account("user@outlook.com", "outlook.office365.com", AuthType.OAUTH_OUTLOOK, id = "acct-outlook")
        assertEquals("", providerLabelFor(emptyList(), listOf(outlook)))

        val orphaned = listOf(folder("INBOX", "INBOX", FolderRole.INBOX, accountId = "acct-gone"))
        assertEquals("", providerLabelFor(orphaned, listOf(outlook)))
    }

    @Test
    fun `providerLabel resolves brands, Outlook, and falls back to the email domain`() {
        assertEquals("Gmail", providerLabel(account("a@gmail.com", "imap.gmail.com")))
        assertEquals("iCloud Mail", providerLabel(account("a@icloud.com", "imap.mail.me.com")))
        assertEquals(
            "Outlook",
            providerLabel(account("a@outlook.com", "outlook.office365.com", AuthType.OAUTH_OUTLOOK)),
        )
        assertEquals("example.org", providerLabel(account("alice@example.org", "imap.example.org")))
    }
}
