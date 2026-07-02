// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.domain.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FolderRoleTest {

    @Test
    fun `INBOX is detected by name regardless of case or attributes`() {
        assertEquals(FolderRole.INBOX, FolderRole.roleOf("INBOX", "INBOX", emptyList()))
        assertEquals(FolderRole.INBOX, FolderRole.roleOf("inbox", "inbox", emptyList()))
    }

    @Test
    fun `special-use attributes take precedence over the name`() {
        assertEquals(FolderRole.SENT, FolderRole.roleOf("X", "Whatever", listOf("\\Sent")))
        assertEquals(FolderRole.DRAFTS, FolderRole.roleOf("X", "Whatever", listOf("\\Drafts")))
        assertEquals(FolderRole.SPAM, FolderRole.roleOf("X", "Whatever", listOf("\\Junk")))
        assertEquals(FolderRole.TRASH, FolderRole.roleOf("X", "Whatever", listOf("\\Trash")))
        assertEquals(FolderRole.ARCHIVE, FolderRole.roleOf("X", "Whatever", listOf("\\Archive")))
    }

    @Test
    fun `falls back to a case-insensitive name match when no attributes are advertised`() {
        assertEquals(FolderRole.SENT, FolderRole.roleOf("[Gmail]/Sent Mail", "Sent Mail", emptyList()))
        assertEquals(FolderRole.DRAFTS, FolderRole.roleOf("Drafts", "Drafts", emptyList()))
        assertEquals(FolderRole.SPAM, FolderRole.roleOf("Junk", "Junk", emptyList()))
        assertEquals(FolderRole.TRASH, FolderRole.roleOf("Deleted Items", "Deleted Items", emptyList()))
        assertEquals(FolderRole.ARCHIVE, FolderRole.roleOf("Archive", "Archive", emptyList()))
        assertEquals(FolderRole.NORMAL, FolderRole.roleOf("Receipts", "Receipts", emptyList()))
    }

    @Test
    fun `isServerSpecial is true only for special-use attributes`() {
        assertTrue(FolderRole.isServerSpecial(listOf("\\Junk")))
        assertTrue(FolderRole.isServerSpecial(listOf("\\All")))
        // Issue #62: \Important (RFC 8457) is what Gmail advertises on [Gmail]/Important.
        assertTrue(FolderRole.isServerSpecial(listOf("\\Important")))
        // Case-insensitive, and ignores non-special-use flags mixed in.
        assertTrue(FolderRole.isServerSpecial(listOf("\\HasNoChildren", "\\drafts")))
        assertFalse(FolderRole.isServerSpecial(emptyList()))
        assertFalse(FolderRole.isServerSpecial(listOf("\\HasNoChildren")))
    }

    // Guards the #65 unified attribute-to-role table: roleOf and isServerSpecial read from one map.
    // Role-bearing attributes drive a role AND mark the folder special; \All, \Flagged and \Important
    // are server-special but role-less (their role comes from the display name — here "Neutral", so
    // NORMAL). Pins that the refactor preserved every existing mapping and added \Important (#62).
    @Test
    fun `every special-use attribute maps to its role and is server-special`() {
        val expectedRole = mapOf(
            "\\Sent" to FolderRole.SENT,
            "\\Drafts" to FolderRole.DRAFTS,
            "\\Junk" to FolderRole.SPAM,
            "\\Trash" to FolderRole.TRASH,
            "\\Archive" to FolderRole.ARCHIVE,
            "\\All" to FolderRole.NORMAL,
            "\\Flagged" to FolderRole.NORMAL,
            "\\Important" to FolderRole.NORMAL,
        )
        expectedRole.forEach { (attribute, role) ->
            assertEquals(role, FolderRole.roleOf("X", "Neutral", listOf(attribute)), "role for $attribute")
            assertTrue(FolderRole.isServerSpecial(listOf(attribute)), "special-use for $attribute")
        }
    }

    // A folder advertising two role-bearing attributes resolves to the earlier table entry (Sent
    // precedes Archive), whatever order the server listed them — preserving the old when-ladder's
    // precedence now that it is table-driven.
    @Test
    fun `role precedence follows the table order, not the folder's attribute order`() {
        assertEquals(FolderRole.SENT, FolderRole.roleOf("X", "Whatever", listOf("\\Archive", "\\Sent")))
        assertEquals(FolderRole.SENT, FolderRole.roleOf("X", "Whatever", listOf("\\Sent", "\\Archive")))
    }

    // A role-less special-use attribute must not suppress the display-name fallback: \All on a folder
    // named "Sent" still classifies as SENT, and Gmail's [Gmail]/Important (\Important, RFC 8457) is
    // special-use yet keeps NORMAL because "Important" is not a friendly role.
    @Test
    fun `a role-less special-use attribute still allows the name fallback`() {
        assertEquals(FolderRole.SENT, FolderRole.roleOf("X", "Sent", listOf("\\All")))
        assertEquals(FolderRole.NORMAL, FolderRole.roleOf("[Gmail]/Important", "Important", listOf("\\Important")))
    }
}
