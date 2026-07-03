// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local

import org.junit.Test
import org.libremail.data.local.entity.DraftEntity
import org.libremail.domain.model.Draft
import org.libremail.domain.model.OutgoingAttachment
import kotlin.test.assertEquals

/**
 * The draft attachment JSON mapper must preserve an inline image's cid↔file pairing across a save and
 * reopen (#77), while still reading an older draft (written before inline images) as a plain
 * attachment. The same `{uri, name, contentId?, isInline?}` serialization also backs the outbox
 * column, so this pins the wire format both use.
 */
class OutgoingAttachmentMapperTest {

    @Test
    fun `draft attachments including an inline image round-trip through the entity`() {
        val draft = Draft(
            id = "d1",
            accountId = "acct",
            to = "bob@example.org",
            cc = "",
            subject = "With image",
            body = "See [image: cat.png]",
            updatedAt = 1_000L,
            bodyHtml = "<p>See <img src=\"cid:cat@libremail\" alt=\"cat.png\"></p>",
            attachments = listOf(
                OutgoingAttachment("content://docs/report", "report.pdf"),
                OutgoingAttachment("content://images/cat", "cat.png", contentId = "cat@libremail", isInline = true),
            ),
        )

        val restored = draft.toEntity().toDomain()

        assertEquals(draft.attachments, restored.attachments)
        assertEquals(draft.bodyHtml, restored.bodyHtml)
    }

    @Test
    fun `a draft json written before inline images reads back as a plain attachment`() {
        val legacy = DraftEntity(
            id = "d",
            accountId = null,
            toAddresses = "x@y.org",
            ccAddresses = "",
            bccAddresses = "",
            subject = "",
            body = "",
            updatedAt = 0L,
            attachments = """[{"uri":"content://f","name":"a.txt"}]""",
            bodyHtml = null,
        )

        assertEquals(
            OutgoingAttachment("content://f", "a.txt", contentId = null, isInline = false),
            legacy.toDomain().attachments.single(),
        )
    }

    @Test
    fun `no attachments serialize to the empty string`() {
        assertEquals("", emptyList<OutgoingAttachment>().toOutgoingAttachmentsJson())
        assertEquals(emptyList(), "".toOutgoingAttachments())
    }
}
