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
    /**
     * True when the server advertises this as a special-use folder (RFC 6154). Lets the drawer tell
     * a provider's built-in folder from a same-named user folder when de-duplicating labels.
     */
    val specialUse: Boolean = false,
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
         * Single source of truth mapping a lowercase IMAP SPECIAL-USE attribute to the role it implies:
         * RFC 6154's six attributes, plus Gmail's `\All` and RFC 8457's `\Important`. A `null` role
         * marks an attribute that flags a folder as server-provisioned (not user-created) without
         * implying one of the friendly [FolderRole]s. [roleOf] returns the first role-bearing entry the
         * folder advertises; [isServerSpecial] treats every key as special-use. Insertion order sets
         * [roleOf]'s precedence when a folder advertises more than one role-bearing attribute.
         */
        private val ATTRIBUTE_ROLES: Map<String, FolderRole?> = linkedMapOf(
            "\\sent" to SENT,
            "\\drafts" to DRAFTS,
            "\\junk" to SPAM,
            "\\trash" to TRASH,
            "\\archive" to ARCHIVE,
            "\\all" to null,
            "\\flagged" to null,
            "\\important" to null,
        )

        /**
         * Classifies a folder from its name and IMAP SPECIAL-USE attributes (RFC 6154). Prefers the
         * server-advertised attribute; falls back to a case-insensitive name match because many
         * servers (and the GreenMail test server) don't advertise SPECIAL-USE.
         */
        fun roleOf(fullName: String, displayName: String, attributes: List<String>): FolderRole {
            if (fullName.equals("INBOX", ignoreCase = true)) return INBOX
            val attrs = attributes.map { it.lowercase() }
            val byAttribute = ATTRIBUTE_ROLES.entries.firstNotNullOfOrNull { (attribute, role) ->
                role?.takeIf { attribute in attrs }
            }
            return byAttribute ?: roleFromDisplayName(displayName)
        }

        /** True when the server advertises any SPECIAL-USE attribute for the folder (RFC 6154). */
        fun isServerSpecial(attributes: List<String>): Boolean = attributes.any { it.lowercase() in ATTRIBUTE_ROLES }

        /** Best-effort role from a folder's display name, for servers without SPECIAL-USE flags. */
        private fun roleFromDisplayName(displayName: String): FolderRole = when (displayName.lowercase().trim()) {
            "sent", "sent mail", "sent items", "sent messages" -> SENT
            "drafts", "draft" -> DRAFTS
            "junk", "spam", "junk e-mail", "junk email", "bulk mail" -> SPAM
            "trash", "deleted", "deleted items", "deleted messages", "bin" -> TRASH
            "archive", "archives", "all mail" -> ARCHIVE
            else -> NORMAL
        }
    }
}
