// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.richtext

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Branch-level coverage for [RichTextHtmlParser] and its CSS/attribute helpers (issue #289). The
 * happy-path round-trips live in [RichTextHtmlTest]; this suite drives the parser's edge cases —
 * malformed tags, stray/empty/unclosed tags, and every arm of the small CSS parsers — directly on
 * the internal helpers and through [RichTextHtml.fromHtml].
 */
class RichTextHtmlParserTest {

    // --- parseCssColor ---

    @Test
    fun `parseCssColor accepts short and long hex and rejects everything else`() {
        assertEquals(0xFFFF0000.toInt(), parseCssColor("#f00"))
        assertEquals(0xFFFF0000.toInt(), parseCssColor("#ff0000"))
        assertEquals(0xFFAABBCC.toInt(), parseCssColor("#abc"))
        // Not a hex literal at all.
        assertNull(parseCssColor("rgb(1,2,3)"))
        assertNull(parseCssColor("red"))
        // A '#' with an unsupported digit count.
        assertNull(parseCssColor("#12"))
        assertNull(parseCssColor("#12345"))
        // Right length, but not valid hex.
        assertNull(parseCssColor("#gggggg"))
    }

    // --- parseFontSizePt ---

    @Test
    fun `parseFontSizePt reads pt directly and converts px, rejecting non-positive or malformed`() {
        assertEquals(14, parseFontSizePt("14pt"))
        assertEquals(12, parseFontSizePt("16px")) // 16 * 3/4
        assertEquals(2, parseFontSizePt("1.5pt")) // rounds to nearest int
        // A zero (or rounding-to-zero) size is dropped.
        assertNull(parseFontSizePt("0px"))
        assertNull(parseFontSizePt("0pt"))
        // No recognizable "<number><unit>".
        assertNull(parseFontSizePt("large"))
        assertNull(parseFontSizePt("12")) // missing unit
        assertNull(parseFontSizePt("12em")) // unsupported unit
    }

    // --- parseInlineStyles / styleForCss ---

    @Test
    fun `parseInlineStyles maps each known property and skips unknown or malformed ones`() {
        assertTrue(parseInlineStyles(null).isEmpty())
        assertEquals(
            listOf<RichStyle>(RichStyle.FontFamily("Arial, sans-serif")),
            parseInlineStyles("font-family:Arial, sans-serif"),
        )
        assertEquals(listOf<RichStyle>(RichStyle.FontSize(14)), parseInlineStyles("font-size:14pt"))
        assertEquals(listOf<RichStyle>(RichStyle.FontColor(0xFFFF0000.toInt())), parseInlineStyles("color:#f00"))
        assertEquals(
            listOf<RichStyle>(RichStyle.Highlight(0xFF00FF00.toInt())),
            parseInlineStyles("background-color:#0f0"),
        )
        // Recognized property, unparseable value -> dropped (font-size / color / background-color arms).
        assertTrue(parseInlineStyles("font-size:huge").isEmpty())
        assertTrue(parseInlineStyles("color:notacolor").isEmpty())
        assertTrue(parseInlineStyles("background-color:zzz").isEmpty())
        // Unknown property -> the `else` arm.
        assertTrue(parseInlineStyles("letter-spacing:2px").isEmpty())
    }

    // --- parseBaseStyle ---

    @Test
    fun `parseBaseStyle reads font-family and font-size and ignores other declarations`() {
        assertEquals(RichBaseStyle("Georgia, serif", 12), parseBaseStyle("font-family:Georgia, serif;font-size:12pt"))
        // An unparseable font-size leaves the size null; an unknown prop is skipped entirely.
        assertEquals(RichBaseStyle("Arial", null), parseBaseStyle("font-family:Arial;font-size:nope;color:red"))
        assertEquals(RichBaseStyle(null, null), parseBaseStyle("color:red"))
    }

    // --- parseTextAlign / richAlignFor ---

    @Test
    fun `parseTextAlign resolves the alignment synonyms and rejects unknown values`() {
        assertNull(parseTextAlign(null))
        assertEquals(RichAlign.START, parseTextAlign("text-align:left"))
        assertEquals(RichAlign.START, parseTextAlign("text-align:start"))
        assertEquals(RichAlign.CENTER, parseTextAlign("text-align:center"))
        assertEquals(RichAlign.END, parseTextAlign("text-align:right"))
        assertEquals(RichAlign.END, parseTextAlign("text-align:end"))
        // A recognized property with an unknown value, and an unrelated property, both yield null.
        assertNull(parseTextAlign("text-align:justify"))
        assertNull(parseTextAlign("color:red"))
    }

    // --- extractHref / extractStyleAttr / unescape ---

    @Test
    fun `extractHref reads double- and single-quoted values and defaults to empty`() {
        assertEquals("http://x", extractHref("href=\"http://x\""))
        assertEquals("http://y", extractHref("href='http://y'"))
        assertEquals("", extractHref("name=\"anchor\""))
    }

    @Test
    fun `extractStyleAttr returns the style value or null when absent`() {
        assertEquals("color:red", extractStyleAttr("span style=\"color:red\""))
        assertNull(extractStyleAttr("span class=\"x\""))
    }

    @Test
    fun `unescape decodes every recognized entity`() {
        // '|' separators keep the space-yielding &nbsp; unambiguous.
        assertEquals("<|>|\"|'|'| |&", unescape("&lt;|&gt;|&quot;|&#39;|&apos;|&nbsp;|&amp;"))
    }

    // --- parse()-level edge cases ---

    @Test
    fun `an unterminated tag stops parsing at the stray angle bracket`() {
        // The '<' with no closing '>' breaks the parse loop; the text seen so far is kept.
        assertEquals("ab", RichTextHtml.fromHtml("ab<b").text)
    }

    @Test
    fun `a stray closing tag with no matching open is ignored`() {
        assertEquals("plain", RichTextHtml.fromHtml("</b>plain").text)
        assertTrue(RichTextHtml.fromHtml("</b>plain").spans.isEmpty())
    }

    @Test
    fun `an empty tag pair records no span`() {
        val restored = RichTextHtml.fromHtml("<b></b>text")
        assertEquals("text", restored.text)
        assertTrue(restored.spans.isEmpty())
    }

    @Test
    fun `a stray closing anchor with no open anchor adds no link`() {
        val restored = RichTextHtml.fromHtml("</a>hi")
        assertEquals("hi", restored.text)
        assertTrue(restored.links.isEmpty())
    }

    @Test
    fun `unclosed inline styles and an unclosed anchor are closed at the end of input`() {
        val bold = RichTextHtml.fromHtml("<b>bold")
        assertEquals("bold", bold.text)
        assertEquals(listOf(RichSpan(0, 4, RichStyle.Bold)), bold.spans)

        val link = RichTextHtml.fromHtml("<a href=\"http://x\">link")
        assertEquals(listOf(RichLink(0, 4, "http://x")), link.links)
    }

    @Test
    fun `an img is dropped when it has no src or a non-cid src, and a closing img tag is a no-op`() {
        assertTrue(RichTextHtml.fromHtml("<img alt=\"x\">").images.isEmpty())
        assertTrue(RichTextHtml.fromHtml("<img src=\"http://x/pic.png\">").images.isEmpty())
        assertTrue(RichTextHtml.fromHtml("a</img>b").images.isEmpty())
    }

    @Test
    fun `a list item outside any list still gets a bullet prefix`() {
        assertEquals("• stray", RichTextHtml.fromHtml("<li>stray</li>").text)
    }

    @Test
    fun `an unknown block tag is ignored but the text it wraps is kept`() {
        assertEquals("kept", RichTextHtml.fromHtml("<section>kept</section>").text)
    }

    @Test
    fun `a div after content acts as a block break, not the base-style wrapper`() {
        val restored = RichTextHtml.fromHtml("<p>a</p><div>b</div>")
        assertEquals("a\nb", restored.text)
        assertNull(restored.baseStyle)
    }

    @Test
    fun `an empty aligned paragraph anchors no alignment range`() {
        val restored = RichTextHtml.fromHtml("<p style=\"text-align:center\"></p><p>body</p>")
        assertEquals("body", restored.text)
        assertTrue(restored.alignments.isEmpty())
    }

    @Test
    fun `spans, links, and alignments that fall entirely in trimmed trailing newlines are dropped`() {
        // The trailing <br> becomes a '\n' that finish() trims, leaving the styled/linked/aligned range
        // past the end of the text, so each channel clamps to empty and is dropped.
        assertTrue(RichTextHtml.fromHtml("a<b><br></b>").spans.isEmpty())
        assertTrue(RichTextHtml.fromHtml("a<a href=\"http://x\"><br></a>").links.isEmpty())
    }
}
