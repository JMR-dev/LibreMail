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
                RichSpan(0, 4, RichStyle.Bold),
                RichSpan(5, 11, RichStyle.Italic),
                RichSpan(12, 17, RichStyle.Underline),
            ),
        )
        assertEquals("<p><b>bold</b> <i>italic</i> <u>under</u></p>", RichTextHtml.toHtml(content))
    }

    @Test
    fun `overlapping styles stay valid html`() {
        val content = RichTextContent(
            text = "abcd",
            spans = listOf(RichSpan(0, 3, RichStyle.Bold), RichSpan(1, 4, RichStyle.Italic)),
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
        assertTrue(RichTextContent("x", spans = listOf(RichSpan(0, 1, RichStyle.Bold))).hasFormatting())
    }

    @Test
    fun `hasFormatting covers the alignment image and base style channels`() {
        assertTrue(RichTextContent("x", alignments = listOf(RichAlignment(0, 1, RichAlign.CENTER))).hasFormatting())
        assertTrue(RichTextContent("[image: a]", images = listOf(RichImage(0, 10, "cid1", "a"))).hasFormatting())
        assertTrue(RichTextContent("x", baseStyle = RichBaseStyle()).hasFormatting())
        assertFalse(RichTextContent("x").hasFormatting())
    }

    @Test
    fun `fromHtml round-trips paragraphs styles lists quotes and links`() {
        listOf(
            RichTextContent("Hello\nWorld"),
            RichTextContent("bold", spans = listOf(RichSpan(0, 4, RichStyle.Bold))),
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
        assertEquals(listOf(RichSpan(8, 11, RichStyle.Bold)), restored.spans)
    }

    @Test
    fun `empty content produces empty html`() {
        assertEquals("", RichTextHtml.toHtml(RichTextContent("")))
    }

    // --- parameterized styles, alignment, images, base style (foundation for the formatting epic) ---

    @Test
    fun `strikethrough renders as s and parses del and strike too`() {
        val content = RichTextContent("gone", spans = listOf(RichSpan(0, 4, RichStyle.Strikethrough)))
        assertEquals("<p><s>gone</s></p>", RichTextHtml.toHtml(content))
        listOf("<p><s>gone</s></p>", "<p><del>gone</del></p>", "<p><strike>gone</strike></p>").forEach { html ->
            assertEquals(content, RichTextHtml.fromHtml(html), html)
        }
    }

    @Test
    fun `parameterized styles on one run merge into a single span tag`() {
        val content = RichTextContent(
            text = "ab",
            spans = listOf(
                RichSpan(0, 2, RichStyle.FontSize(14)),
                RichSpan(0, 2, RichStyle.FontColor(0xFFFF0000.toInt())),
                RichSpan(0, 2, RichStyle.Highlight(0xFFFFFF00.toInt())),
                RichSpan(0, 2, RichStyle.FontFamily("Georgia, serif")),
            ),
        )
        assertEquals(
            "<p><span style=\"font-family:Georgia, serif;font-size:14pt;color:#ff0000;" +
                "background-color:#ffff00\">ab</span></p>",
            RichTextHtml.toHtml(content),
        )
        assertRoundTrips(content)
    }

    @Test
    fun `every new style and channel round-trips and keeps hasFormatting true`() {
        listOf(
            RichTextContent("struck", spans = listOf(RichSpan(0, 6, RichStyle.Strikethrough))),
            RichTextContent("serif", spans = listOf(RichSpan(0, 5, RichStyle.FontFamily("Georgia, serif")))),
            RichTextContent("sized", spans = listOf(RichSpan(0, 5, RichStyle.FontSize(18)))),
            RichTextContent("red", spans = listOf(RichSpan(0, 3, RichStyle.FontColor(0xFFCC0000.toInt())))),
            RichTextContent("hi", spans = listOf(RichSpan(0, 2, RichStyle.Highlight(0xFFFFFF00.toInt())))),
            RichTextContent(
                text = "mixed run",
                spans = listOf(
                    RichSpan(0, 5, RichStyle.Bold),
                    RichSpan(2, 9, RichStyle.FontSize(12)),
                    RichSpan(2, 5, RichStyle.FontColor(0xFF336699.toInt())),
                ),
            ),
            RichTextContent("left\ncentered", alignments = listOf(RichAlignment(5, 13, RichAlign.CENTER))),
            RichTextContent("a\nb\nc", alignments = listOf(RichAlignment(0, 3, RichAlign.END))),
            RichTextContent("• Milk\n• Eggs", alignments = listOf(RichAlignment(0, 13, RichAlign.CENTER))),
            RichTextContent(
                text = "see [image: cat.png] here",
                images = listOf(RichImage(4, 20, "img1@libremail", "cat.png")),
            ),
            RichTextContent("plain", baseStyle = RichBaseStyle(fontCss = "Arial, sans-serif", fontSizePt = 12)),
            RichTextContent("plain", baseStyle = RichBaseStyle(fontSizePt = 11)),
            RichTextContent(
                text = "• item\nnote [image: dog.png]",
                spans = listOf(RichSpan(2, 6, RichStyle.Bold), RichSpan(7, 11, RichStyle.FontSize(10))),
                alignments = listOf(RichAlignment(0, 6, RichAlign.CENTER)),
                images = listOf(RichImage(12, 28, "dog@mail", "dog.png")),
                baseStyle = RichBaseStyle("Georgia, serif", 12),
            ),
        ).forEach(::assertRoundTrips)
    }

    @Test
    fun `alignment splits merged paragraphs and marks list items`() {
        val paragraphs = RichTextContent("a\nb", alignments = listOf(RichAlignment(2, 3, RichAlign.CENTER)))
        assertEquals("<p>a</p><p style=\"text-align:center\">b</p>", RichTextHtml.toHtml(paragraphs))
        val list = RichTextContent("• Milk\n• Eggs", alignments = listOf(RichAlignment(0, 13, RichAlign.CENTER)))
        assertEquals(
            "<ul><li style=\"text-align:center\">Milk</li><li style=\"text-align:center\">Eggs</li></ul>",
            RichTextHtml.toHtml(list),
        )
    }

    @Test
    fun `parser accepts start and end alignment synonyms`() {
        val restored = RichTextHtml.fromHtml("<p style=\"text-align:start\">a</p><p style=\"text-align:end\">b</p>")
        assertEquals("a\nb", restored.text)
        assertEquals(
            listOf(RichAlignment(0, 1, RichAlign.START), RichAlignment(2, 3, RichAlign.END)),
            restored.alignments,
        )
    }

    @Test
    fun `images replace their token and keep surrounding style wrappers`() {
        val content = RichTextContent(
            text = "[image: pic]",
            spans = listOf(RichSpan(0, 12, RichStyle.Bold)),
            links = listOf(RichLink(0, 12, "http://x")),
            images = listOf(RichImage(0, 12, "c1", "pic")),
        )
        assertEquals(
            "<p><a href=\"http://x\"><b><img src=\"cid:c1\" alt=\"pic\"></b></a></p>",
            RichTextHtml.toHtml(content),
        )
        assertRoundTrips(content)
    }

    @Test
    fun `parser converts px sizes and short hex colors`() {
        val restored = RichTextHtml.fromHtml("<p><span style=\"font-size:16px;color:#f00\">x</span></p>")
        assertEquals(
            setOf(
                RichSpan(0, 1, RichStyle.FontSize(12)),
                RichSpan(0, 1, RichStyle.FontColor(0xFFFF0000.toInt())),
            ),
            restored.spans.toSet(),
        )
    }

    @Test
    fun `unknown css properties are ignored without dropping the run`() {
        val restored = RichTextHtml.fromHtml("<p><span style=\"mso-spacerun:yes;letter-spacing:2px\">kept</span></p>")
        assertEquals("kept", restored.text)
        assertTrue(restored.spans.isEmpty(), restored.spans.toString())
    }

    @Test
    fun `font families with quotes round-trip through attribute escaping`() {
        val family = RichStyle.FontFamily("\"Open Sans\", sans-serif")
        val content = RichTextContent("x", spans = listOf(RichSpan(0, 1, family)))
        val html = RichTextHtml.toHtml(content)
        assertTrue(html.contains("&quot;Open Sans&quot;"), html)
        assertRoundTrips(content)
    }

    @Test
    fun `base style wrapper adds no stray text or newlines`() {
        val restored = RichTextHtml.fromHtml("<div style=\"font-size:12pt\"><p>a<br>b</p></div>")
        assertEquals("a\nb", restored.text)
        assertEquals(RichBaseStyle(fontSizePt = 12), restored.baseStyle)
    }

    @Test
    fun `base style survives empty text`() {
        val content = RichTextContent("", baseStyle = RichBaseStyle(fontCss = "Georgia, serif"))
        val html = RichTextHtml.toHtml(content)
        assertEquals("<div style=\"font-family:Georgia, serif\"></div>", html)
        assertEquals(content, RichTextHtml.fromHtml(html))
    }

    /**
     * The strict guarantee every channel needs: serialize, parse back, get the same model, and stay
     * "formatted" — ComposeViewModel.normalizedHtml() silently drops any HTML that parses back as
     * unformatted, so a violation here means user formatting is destroyed on From-account switches.
     */
    private fun assertRoundTrips(original: RichTextContent) {
        assertTrue(original.hasFormatting(), "hasFormatting must be true for: $original")
        val html = RichTextHtml.toHtml(original)
        val restored = RichTextHtml.fromHtml(html)
        assertEquals(original.text, restored.text, "text of: $html")
        assertEquals(original.spans.toSet(), restored.spans.toSet(), "spans of: $html")
        assertEquals(original.links.toSet(), restored.links.toSet(), "links of: $html")
        assertEquals(original.alignments, restored.alignments, "alignments of: $html")
        assertEquals(original.images, restored.images, "images of: $html")
        assertEquals(original.baseStyle, restored.baseStyle, "baseStyle of: $html")
        assertTrue(restored.hasFormatting(), "formatting must survive reparsing: $html")
    }
}
