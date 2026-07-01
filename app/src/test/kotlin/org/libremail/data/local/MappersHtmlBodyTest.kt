// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local

import org.junit.Test
import org.libremail.data.local.entity.OutboxEntity
import org.libremail.domain.model.Draft
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MappersHtmlBodyTest {

    @Test
    fun `draft html body round-trips through the entity`() {
        val draft = Draft(
            id = "d1",
            accountId = "acct",
            to = "a@x.com",
            cc = "",
            subject = "Hi",
            body = "Hello",
            updatedAt = 1L,
            bodyHtml = "<p>Hello <b>there</b></p>",
        )

        val restored = draft.toEntity().toDomain()

        assertEquals("<p>Hello <b>there</b></p>", restored.bodyHtml)
        assertEquals("Hello", restored.body)
    }

    @Test
    fun `a plaintext draft keeps a null html body`() {
        val draft = Draft("d2", "acct", "a@x.com", "", "Hi", "Hello", 1L)
        assertNull(draft.toEntity().toDomain().bodyHtml)
    }

    @Test
    fun `outbox entity maps its html body to the domain`() {
        val entity = OutboxEntity(
            id = "o1",
            accountId = "acct",
            toAddresses = "a@x.com",
            ccAddresses = "",
            subject = "Hi",
            body = "Hello",
            createdAt = 1L,
            bodyHtml = "<p>Hi</p>",
        )

        assertEquals("<p>Hi</p>", entity.toDomain().bodyHtml)
    }
}
