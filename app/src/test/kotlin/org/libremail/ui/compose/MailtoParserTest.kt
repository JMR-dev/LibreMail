// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.compose

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MailtoParserTest {

    @Test
    fun `single recipient with no headers`() {
        val prefill = MailtoParser.parse("mailto:alice@example.com")

        assertEquals("alice@example.com", prefill.to)
        assertEquals("", prefill.cc)
        assertEquals("", prefill.bcc)
        assertEquals("", prefill.subject)
        assertEquals("", prefill.body)
    }

    @Test
    fun `multiple recipients before the query are comma-separated`() {
        val prefill = MailtoParser.parse("mailto:alice@example.com,bob@example.com,carol@example.com")

        assertEquals("alice@example.com, bob@example.com, carol@example.com", prefill.to)
    }

    @Test
    fun `parses subject, body, cc and bcc header fields`() {
        val prefill = MailtoParser.parse(
            "mailto:alice@example.com?subject=Hello&body=How%20are%20you%3F&cc=carol@example.com&bcc=dan@example.com",
        )

        assertEquals("alice@example.com", prefill.to)
        assertEquals("carol@example.com", prefill.cc)
        assertEquals("dan@example.com", prefill.bcc)
        assertEquals("Hello", prefill.subject)
        assertEquals("How are you?", prefill.body)
    }

    @Test
    fun `cc and bcc header fields may list multiple recipients`() {
        val prefill = MailtoParser.parse(
            "mailto:alice@example.com?cc=carol@example.com,carl@example.com&bcc=dan@example.com,dana@example.com",
        )

        assertEquals("carol@example.com, carl@example.com", prefill.cc)
        assertEquals("dan@example.com, dana@example.com", prefill.bcc)
    }

    @Test
    fun `a to header field merges with recipients before the query`() {
        val prefill = MailtoParser.parse("mailto:alice@example.com?to=bob@example.com&subject=Hi")

        assertEquals("alice@example.com, bob@example.com", prefill.to)
        assertEquals("Hi", prefill.subject)
    }

    @Test
    fun `decodes percent-encoded subject and multi-line body`() {
        val prefill = MailtoParser.parse(
            "mailto:a@example.com?subject=Q1%20%26%20Q2%20review&body=Line%20one%0ALine%20two",
        )

        assertEquals("Q1 & Q2 review", prefill.subject)
        assertEquals("Line one\nLine two", prefill.body)
    }

    @Test
    fun `decodes percent-encoded addresses`() {
        // A percent-encoded '@' (%40) in the address must decode.
        val prefill = MailtoParser.parse("mailto:alice%40example.com")

        assertEquals("alice@example.com", prefill.to)
    }

    @Test
    fun `preserves a literal plus in addresses instead of decoding it to a space`() {
        // RFC 6068 uses %20 for space, so '+' is a real character (unlike form-encoding).
        val prefill = MailtoParser.parse("mailto:alice+newsletter@example.com?body=a+b")

        assertEquals("alice+newsletter@example.com", prefill.to)
        assertEquals("a+b", prefill.body)
    }

    @Test
    fun `is case-insensitive about the scheme and header names`() {
        val prefill = MailtoParser.parse("MAILTO:alice@example.com?SUBJECT=Hi&BODY=There&CC=c@example.com")

        assertEquals("alice@example.com", prefill.to)
        assertEquals("c@example.com", prefill.cc)
        assertEquals("Hi", prefill.subject)
        assertEquals("There", prefill.body)
    }

    @Test
    fun `parses a bare address without the scheme prefix`() {
        val prefill = MailtoParser.parse("alice@example.com")

        assertEquals("alice@example.com", prefill.to)
    }

    @Test
    fun `empty mailto yields an empty prefill`() {
        val prefill = MailtoParser.parse("mailto:")

        assertTrue(prefill.isEmpty)
    }

    @Test
    fun `blank list separators are ignored`() {
        val prefill = MailtoParser.parse("mailto:alice@example.com,,bob@example.com,")

        assertEquals("alice@example.com, bob@example.com", prefill.to)
    }

    @Test
    fun `unknown header fields are ignored`() {
        val prefill = MailtoParser.parse("mailto:a@example.com?subject=Hi&in-reply-to=%3Cabc%3E&keywords=x")

        assertEquals("Hi", prefill.subject)
        assertEquals("", prefill.body)
        assertTrue(prefill.cc.isEmpty())
    }

    @Test
    fun `a malformed percent-escape is passed through verbatim`() {
        val prefill = MailtoParser.parse("mailto:a@example.com?subject=100%25%20done%20%GG")

        assertEquals("100% done %GG", prefill.subject)
    }
}
