// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data

import org.junit.Test
import org.libremail.domain.model.ReplyMode
import org.libremail.mail.ReplyContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReplyBuilderTest {

    private fun context(
        from: String = "alice@example.org",
        to: List<String> = listOf("me@example.org"),
        cc: List<String> = emptyList(),
        subject: String = "Lunch",
        body: String = "Original body",
        isHtml: Boolean = false,
    ) = ReplyContext(
        fromEmail = from,
        toRecipients = to,
        ccRecipients = cc,
        subject = subject,
        sentDateMillis = 0L,
        body = body,
        isHtml = isHtml,
    )

    @Test
    fun `reply targets the sender and quotes the original`() {
        val result = ReplyBuilder.build(context(), ReplyMode.REPLY, selfEmail = "me@example.org")

        assertEquals("alice@example.org", result.to)
        assertEquals("", result.cc)
        assertEquals("Re: Lunch", result.subject)
        assertTrue(result.body.contains("Original body"), "body=${result.body}")
    }

    @Test
    fun `reply does not stack a second Re prefix`() {
        val result = ReplyBuilder.build(context(subject = "Re: Lunch"), ReplyMode.REPLY, "me@example.org")

        assertEquals("Re: Lunch", result.subject)
    }

    @Test
    fun `reply all ccs everyone except self and the original sender`() {
        val result = ReplyBuilder.build(
            context(
                from = "alice@example.org",
                to = listOf("me@example.org", "bob@example.org"),
                cc = listOf("carol@example.org", "alice@example.org"),
            ),
            ReplyMode.REPLY_ALL,
            selfEmail = "me@example.org",
        )

        assertEquals("alice@example.org", result.to)
        assertEquals(setOf("bob@example.org", "carol@example.org"), result.cc.split(", ").toSet())
    }

    @Test
    fun `reply all excludes self case-insensitively`() {
        val result = ReplyBuilder.build(
            context(to = listOf("ME@Example.org")),
            ReplyMode.REPLY_ALL,
            selfEmail = "me@example.org",
        )

        assertEquals("", result.cc)
    }

    @Test
    fun `forward clears recipients, prefixes Fwd, and includes the original`() {
        val result = ReplyBuilder.build(context(), ReplyMode.FORWARD, "me@example.org")

        assertEquals("", result.to)
        assertEquals("Fwd: Lunch", result.subject)
        assertTrue(result.body.contains("Forwarded message"), "body=${result.body}")
        assertTrue(result.body.contains("Original body"), "body=${result.body}")
    }

    @Test
    fun `forward lists the original recipients in the quoted header`() {
        val result = ReplyBuilder.build(
            context(to = listOf("ada@example.org", "team@example.org")),
            ReplyMode.FORWARD,
            selfEmail = "me@example.org",
        )

        assertTrue(result.body.contains("ada@example.org"), "body=${result.body}")
        assertTrue(result.body.contains("team@example.org"), "body=${result.body}")
    }

    @Test
    fun `html bodies are stripped to text when quoting`() {
        val result = ReplyBuilder.build(
            context(body = "<p>Hello <b>there</b></p>", isHtml = true),
            ReplyMode.REPLY,
            "me@example.org",
        )

        assertFalse(result.body.contains("<"), "body=${result.body}")
        assertTrue(result.body.contains("Hello"), "body=${result.body}")
        assertTrue(result.body.contains("there"), "body=${result.body}")
    }

    @Test
    fun `html original is quoted into a blockquote without leaking original tags`() {
        val result = ReplyBuilder.build(
            context(body = "<p>Hello <b>there</b></p>", isHtml = true),
            ReplyMode.REPLY,
            "me@example.org",
        )

        // The HTML alternative wraps the (tag-stripped) original in a blockquote — never raw tags.
        assertTrue(result.bodyHtml.contains("<blockquote>"), "html=${result.bodyHtml}")
        assertTrue(result.bodyHtml.contains("Hello there"), "html=${result.bodyHtml}")
        assertFalse(result.bodyHtml.contains("<p>Hello"), "html=${result.bodyHtml}")
    }

    @Test
    fun `plaintext reply also carries an html blockquote alternative`() {
        val result = ReplyBuilder.build(context(body = "First line\nSecond line"), ReplyMode.REPLY, "me@example.org")

        assertTrue(result.bodyHtml.contains("<blockquote>"), "html=${result.bodyHtml}")
        assertTrue(result.bodyHtml.contains("First line"), "html=${result.bodyHtml}")
    }
}
