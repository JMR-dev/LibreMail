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
    fun `decodes numeric character references in decimal and hex`() {
        assertEquals("It’s fine", HtmlToText.convert("It&#8217;s fine"))
        assertEquals("It’s fine", HtmlToText.convert("It&#x2019;s fine"))
    }

    @Test
    fun `decoding is single-pass so a decoded character is never re-decoded`() {
        assertEquals("&lt;b&gt;", HtmlToText.convert("&amp;lt;b&amp;gt;"))
    }

    @Test
    fun `unknown or malformed references are left as-is`() {
        assertEquals("&bogus; &#; stays", HtmlToText.convert("&bogus; &#; stays"))
    }

    @Test
    fun `collapses excess whitespace`() {
        val converted = HtmlToText.convert("<p>Hello    there</p>\n\n\n<p>bye</p>")
        assertTrue(converted.contains("Hello there"), converted)
        assertFalse(converted.contains("Hello    there"), converted)
    }

    @Test
    fun `out-of-range and surrogate numeric references are left verbatim`() {
        // 0, a lone surrogate, and code points above U+10FFFF are not valid scalar values, so both
        // the decimal and hex forms are kept as-is (exercises every branch of the range/surrogate guard).
        assertEquals("&#0;", HtmlToText.convert("&#0;"))
        assertEquals("&#55296;", HtmlToText.convert("&#55296;")) // 0xD800, a lone high surrogate
        assertEquals("&#xD800;", HtmlToText.convert("&#xD800;"))
        assertEquals("&#9999999;", HtmlToText.convert("&#9999999;")) // > U+10FFFF (decimal)
        assertEquals("&#xffffff;", HtmlToText.convert("&#xffffff;")) // > U+10FFFF (hex)
    }

    @Test
    fun `supplementary-plane numeric references decode to their character`() {
        // U+1F600 GRINNING FACE — a valid supplementary-plane scalar value in decimal and hex.
        assertEquals("😀", HtmlToText.convert("&#128512;"))
        assertEquals("😀", HtmlToText.convert("&#x1F600;"))
    }

    @Test
    fun `named dash entities are decoded`() {
        assertEquals("a—b–c", HtmlToText.convert("a&mdash;b&ndash;c"))
    }
}
