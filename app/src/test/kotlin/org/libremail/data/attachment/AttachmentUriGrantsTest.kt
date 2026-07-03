// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.attachment

import org.junit.Test
import kotlin.test.assertEquals

/**
 * The release decision that [AttachmentUriGrants] runs after a draft/outbox row is deleted: a picked
 * URI's persistable grant is released only when no *remaining* draft or outbox row still references
 * it. The Android release call itself (`releasePersistableUriPermission`) is a thin wrapper; the
 * decision here is the part worth pinning.
 */
class AttachmentUriGrantsTest {

    @Test
    fun `releases a uri referenced by no remaining row`() {
        assertEquals(
            listOf("content://a"),
            unreferencedUris(candidates = listOf("content://a"), referenced = emptySet()),
        )
    }

    @Test
    fun `keeps a uri still referenced by another live draft or outbox row`() {
        // content://shared is attached to a second draft that is still around, so its grant must stay.
        assertEquals(
            listOf("content://gone"),
            unreferencedUris(
                candidates = listOf("content://gone", "content://shared"),
                referenced = setOf("content://shared"),
            ),
        )
    }

    @Test
    fun `deduplicates candidate uris`() {
        assertEquals(
            listOf("content://a"),
            unreferencedUris(candidates = listOf("content://a", "content://a"), referenced = emptySet()),
        )
    }

    @Test
    fun `releases nothing when every candidate is still referenced`() {
        assertEquals(
            emptyList(),
            unreferencedUris(candidates = listOf("content://a"), referenced = setOf("content://a")),
        )
    }
}
