// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.domain.model

/** A mail folder (IMAP mailbox) belonging to an account. */
data class Folder(
    val accountId: String,
    /** Server path including hierarchy, e.g. "INBOX" or "[Gmail]/Sent Mail". Used to open the folder. */
    val fullName: String,
    /** The leaf name shown to the user, e.g. "Sent Mail". */
    val displayName: String,
    val role: FolderRole,
    /** False for \Noselect containers (e.g. Gmail's "[Gmail]" parent) that hold no messages. */
    val selectable: Boolean,
)

/**
 * The well-known role of a folder, used to surface standard folders with friendly names and icons.
 * Enum order is the drawer display order (standard roles first, [NORMAL] last).
 */
enum class FolderRole {
    INBOX,
    SENT,
    DRAFTS,
    ARCHIVE,
    SPAM,
    TRASH,
    NORMAL,
    ;

    companion object {
        /**
         * Classifies a folder from its name and IMAP SPECIAL-USE attributes (RFC 6154). Prefers the
         * server-advertised attribute; falls back to a case-insensitive name match because many
         * servers (and the GreenMail test server) don't advertise SPECIAL-USE.
         */
        fun roleOf(fullName: String, displayName: String, attributes: List<String>): FolderRole {
            if (fullName.equals("INBOX", ignoreCase = true)) return INBOX
            val attrs = attributes.map { it.lowercase() }
            when {
                "\\sent" in attrs -> return SENT
                "\\drafts" in attrs -> return DRAFTS
                "\\junk" in attrs -> return SPAM
                "\\trash" in attrs -> return TRASH
                "\\archive" in attrs -> return ARCHIVE
            }
            return when (displayName.lowercase().trim()) {
                "sent", "sent mail", "sent items", "sent messages" -> SENT
                "drafts", "draft" -> DRAFTS
                "junk", "spam", "junk e-mail", "junk email", "bulk mail" -> SPAM
                "trash", "deleted", "deleted items", "deleted messages", "bin" -> TRASH
                "archive", "archives", "all mail" -> ARCHIVE
                else -> NORMAL
            }
        }
    }
}
