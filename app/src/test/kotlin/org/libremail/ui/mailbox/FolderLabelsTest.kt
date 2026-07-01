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
    ) = Folder("acct", fullName, displayName, role, selectable = true, specialUse = specialUse)

    private fun account(email: String, imapHost: String, authType: AuthType = AuthType.PASSWORD_IMAP) = Account(
        id = "acct",
        email = email,
        displayName = email,
        authType = authType,
        imap = ServerConfig(imapHost, 993, MailSecurity.SSL_TLS),
        smtp = ServerConfig("smtp", 587, MailSecurity.STARTTLS),
    )

    /** Builds base labels the way the drawer does: the friendly role name, else the raw display name. */
    private fun baseLabelsOf(folders: List<Folder>, friendly: Map<FolderRole, String>) =
        folders.associate { it.fullName to (friendly[it.role] ?: it.displayName) }

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
