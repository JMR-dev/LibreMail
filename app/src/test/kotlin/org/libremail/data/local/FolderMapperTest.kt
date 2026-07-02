// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local

import org.junit.Test
import org.libremail.domain.model.FolderRole
import org.libremail.mail.FetchedFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the one production link between a server LIST response and a persisted folder:
 * [FetchedFolder.toEntity] derives the folder's role (via [FolderRole.roleOf]) and its server
 * special-use flag (via [FolderRole.isServerSpecial]) from the IMAP attributes, and toDomain carries
 * that flag back out. Guards the mutation the PR #54 review found: dropping the specialUse assignment
 * still compiles (the column defaults to false) and silently persists a non-special folder for every
 * server folder.
 */
class FolderMapperTest {

    private fun entityFor(attributes: List<String>, displayName: String = "Neutral") = FetchedFolder(
        fullName = "Parent/$displayName",
        displayName = displayName,
        attributes = attributes,
        selectable = true,
    ).toEntity(accountId = "acct", sortOrder = 3)

    @Test
    fun `role-bearing special-use attributes map to their role and mark the folder special`() {
        val roleByAttribute = mapOf(
            "\\Sent" to FolderRole.SENT,
            "\\Drafts" to FolderRole.DRAFTS,
            "\\Junk" to FolderRole.SPAM,
            "\\Trash" to FolderRole.TRASH,
            "\\Archive" to FolderRole.ARCHIVE,
        )
        roleByAttribute.forEach { (attribute, expectedRole) ->
            val entity = entityFor(listOf(attribute))
            assertEquals(expectedRole.name, entity.role, "role for $attribute")
            assertTrue(entity.specialUse, "specialUse for $attribute")
        }
    }

    @Test
    fun `the all-mail and starred attributes mark the folder special but drive no role of their own`() {
        // Gmail's "All Mail" (\All) and "Starred" (\Flagged) are server special-use, yet roleOf maps
        // neither to a role (the role still comes from the name — here neutral, so NORMAL). specialUse
        // and role are independent axes; this documents the current wiring ahead of the #65 refactor.
        listOf("\\All", "\\Flagged").forEach { attribute ->
            val entity = entityFor(listOf(attribute))
            assertTrue(entity.specialUse, "specialUse for $attribute")
            assertEquals(FolderRole.NORMAL.name, entity.role, "role for $attribute")
        }
    }

    @Test
    fun `a folder with no special-use attributes is not marked special`() {
        val entity = entityFor(attributes = emptyList(), displayName = "Receipts")
        assertFalse(entity.specialUse)
        assertEquals(FolderRole.NORMAL.name, entity.role)
    }

    @Test
    fun `a non special-use list flag alone does not mark the folder special`() {
        // \HasNoChildren is a structural LIST flag, not an RFC 6154 SPECIAL-USE attribute.
        assertFalse(entityFor(listOf("\\HasNoChildren"), displayName = "Receipts").specialUse)
    }

    @Test
    fun `the server sort order is carried onto the persisted entity`() {
        assertEquals(3, entityFor(listOf("\\Sent")).sortOrder)
    }

    @Test
    fun `toDomain carries the special-use flag back out for both values`() {
        assertTrue(entityFor(listOf("\\Drafts")).toDomain().specialUse)
        assertFalse(entityFor(emptyList(), displayName = "Receipts").toDomain().specialUse)
    }
}
