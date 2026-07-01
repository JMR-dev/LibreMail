// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HtmlToTextTest {

    @Test
    fun `strips inline tags and keeps the words`() {
        assertEquals("Hello world", HtmlToText.convert("<p>Hello <b>world</b></p>"))
    }

    @Test
    fun `br and paragraphs become line breaks`() {
        assertEquals("Line one\nLine two", HtmlToText.convert("Line one<br>Line two"))
        assertEquals("A\n\nB", HtmlToText.convert("<p>A</p><p>B</p>"))
    }

    @Test
    fun `list items gain bullet markers`() {
        assertEquals("• One\n• Two", HtmlToText.convert("<ul><li>One</li><li>Two</li></ul>"))
    }

    @Test
    fun `decodes entities and drops script and style content`() {
        assertEquals("Tom & Jerry", HtmlToText.convert("Tom &amp; Jerry<style>.x{color:red}</style>"))
        val converted = HtmlToText.convert("<script>alert('x')</script>Safe")
        assertEquals("Safe", converted)
        assertFalse(converted.contains("alert"))
    }

    @Test
    fun `collapses excess whitespace`() {
        val converted = HtmlToText.convert("<p>Hello    there</p>\n\n\n<p>bye</p>")
        assertTrue(converted.contains("Hello there"), converted)
        assertFalse(converted.contains("Hello    there"), converted)
    }
}
