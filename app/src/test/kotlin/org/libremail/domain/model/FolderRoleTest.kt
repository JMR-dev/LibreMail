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
        // Case-insensitive, and ignores non-special-use flags mixed in.
        assertTrue(FolderRole.isServerSpecial(listOf("\\HasNoChildren", "\\drafts")))
        assertFalse(FolderRole.isServerSpecial(emptyList()))
        assertFalse(FolderRole.isServerSpecial(listOf("\\HasNoChildren")))
    }
}
