// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.richtext

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RichTextHtmlTest {

    @Test
    fun `plain paragraphs become p with br line breaks`() {
        val html = RichTextHtml.toHtml(RichTextContent("Hello\nWorld"))
        assertEquals("<p>Hello<br>World</p>", html)
    }

    @Test
    fun `inline styles nest as b i u`() {
        val content = RichTextContent(
            text = "bold italic under",
            spans = listOf(
                RichSpan(0, 4, RichStyle.BOLD),
                RichSpan(5, 11, RichStyle.ITALIC),
                RichSpan(12, 17, RichStyle.UNDERLINE),
            ),
        )
        assertEquals("<p><b>bold</b> <i>italic</i> <u>under</u></p>", RichTextHtml.toHtml(content))
    }

    @Test
    fun `overlapping styles stay valid html`() {
        val content = RichTextContent(
            text = "abcd",
            spans = listOf(RichSpan(0, 3, RichStyle.BOLD), RichSpan(1, 4, RichStyle.ITALIC)),
        )
        // b over [0,3), i over [1,4): every run fully closes its tags, so nesting is always valid.
        assertEquals("<p><b>a</b><b><i>bc</i></b><i>d</i></p>", RichTextHtml.toHtml(content))
    }

    @Test
    fun `bulleted and numbered lists and quotes map to block tags`() {
        assertEquals("<ul><li>Milk</li><li>Eggs</li></ul>", RichTextHtml.toHtml(RichTextContent("• Milk\n• Eggs")))
        assertEquals("<ol><li>One</li><li>Two</li></ol>", RichTextHtml.toHtml(RichTextContent("1. One\n2. Two")))
        assertEquals("<blockquote>a<br>b</blockquote>", RichTextHtml.toHtml(RichTextContent("> a\n> b")))
    }

    @Test
    fun `links render as anchors and text is html escaped`() {
        val content = RichTextContent("a<b>&c", links = listOf(RichLink(0, 1, "http://x?y=1&z")))
        val html = RichTextHtml.toHtml(content)
        assertTrue(html.contains("<a href=\"http://x?y=1&amp;z\">a</a>"), html)
        assertTrue(html.contains("&lt;b&gt;&amp;c"), html)
    }

    @Test
    fun `plaintext keeps the readable markers`() {
        assertEquals("• Milk\n> quote", RichTextHtml.toPlainText(RichTextContent("• Milk\n> quote")))
    }

    @Test
    fun `hasFormatting is false for unstyled markerless text`() {
        assertFalse(RichTextContent("just words\nmore words").hasFormatting())
        assertTrue(RichTextContent("• bullet").hasFormatting())
        assertTrue(RichTextContent("x", spans = listOf(RichSpan(0, 1, RichStyle.BOLD))).hasFormatting())
    }

    @Test
    fun `fromHtml round-trips paragraphs styles lists quotes and links`() {
        listOf(
            RichTextContent("Hello\nWorld"),
            RichTextContent("bold", spans = listOf(RichSpan(0, 4, RichStyle.BOLD))),
            RichTextContent("• Milk\n• Eggs"),
            RichTextContent("1. One\n2. Two"),
            RichTextContent("> a\n> b"),
            RichTextContent("see here", links = listOf(RichLink(4, 8, "http://example.com"))),
            RichTextContent("a<b>&c"),
        ).forEach { original ->
            val restored = RichTextHtml.fromHtml(RichTextHtml.toHtml(original))
            assertEquals(original.text, restored.text, "text: $original")
            assertEquals(
                original.spans.sortedBy(RichSpan::start),
                restored.spans.sortedBy(RichSpan::start),
                "spans: $original",
            )
            assertEquals(
                original.links.sortedBy(RichLink::start),
                restored.links.sortedBy(RichLink::start),
                "links: $original",
            )
        }
    }

    @Test
    fun `fromHtml tolerates strong em and pretty-printed whitespace`() {
        val restored = RichTextHtml.fromHtml("<ul>\n  <li>One</li>\n  <li><strong>Two</strong></li>\n</ul>")
        assertEquals("• One\n• Two", restored.text)
        // "Two" occupies [8,11) of "• One\n• Two".
        assertEquals(listOf(RichSpan(8, 11, RichStyle.BOLD)), restored.spans)
    }

    @Test
    fun `empty content produces empty html`() {
        assertEquals("", RichTextHtml.toHtml(RichTextContent("")))
    }
}
