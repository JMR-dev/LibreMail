// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.sp
import org.junit.Test
import org.libremail.richtext.BlockMarker
import org.libremail.richtext.RichAlign
import org.libremail.richtext.RichAlignment
import org.libremail.richtext.RichBaseStyle
import org.libremail.richtext.RichImage
import org.libremail.richtext.RichLink
import org.libremail.richtext.RichSpan
import org.libremail.richtext.RichStyle
import org.libremail.richtext.RichTextContent
import org.libremail.richtext.RichTextEditing
import org.libremail.richtext.RichTextHtml
import org.libremail.richtext.imageToken
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the Compose-editor glue in `RichTextEditor.kt`: the [TextFieldValue] <->
 * [RichTextContent] conversions, the toolbar's editing ops ([applyStyle]/[applyBlock]/[applyLink]),
 * and the active/inactive predicate `FormattingToolbar` uses to light up its buttons. Everything
 * exercised here operates on plain [TextFieldValue]/[androidx.compose.ui.text.AnnotatedString]/[Color]
 * values, so it all runs on the JVM - no emulator, no Compose runtime needed.
 */
class RichTextEditorTest {

    private val linkColor = Color(0xFF3355FF)
    private val noFont: (String) -> FontFamily? = { null }

    private fun field(text: String, selection: TextRange = TextRange(text.length)) = TextFieldValue(text, selection)

    // --- RichTextContent.toAnnotatedString() <-> AnnotatedString.toRichContent() round trip ---

    @Test
    fun `simple styles round-trip through spanStyles`() {
        val content = RichTextContent(
            text = "bold ital under struck",
            spans = listOf(
                RichSpan(0, 4, RichStyle.Bold),
                RichSpan(5, 9, RichStyle.Italic),
                RichSpan(10, 15, RichStyle.Underline),
                RichSpan(16, 23, RichStyle.Strikethrough),
            ),
        )
        val restored = content.toAnnotatedString(linkColor, noFont).toRichContent()
        assertEquals(content.text, restored.text)
        assertEquals(content.spans.toSet(), restored.spans.toSet())
    }

    @Test
    fun `parameterized styles on the same run round-trip through the STYLE_TAG annotation`() {
        val content = RichTextContent(
            text = "styled",
            spans = listOf(
                RichSpan(0, 6, RichStyle.FontFamily("Georgia, serif")),
                RichSpan(0, 6, RichStyle.FontSize(18)),
                RichSpan(0, 6, RichStyle.FontColor(0xFFCC0000.toInt())),
                RichSpan(0, 6, RichStyle.Highlight(0xFFFFFF00.toInt())),
            ),
        )
        val restored = content.toAnnotatedString(linkColor, noFont).toRichContent()
        assertEquals(content.spans.toSet(), restored.spans.toSet())
    }

    @Test
    fun `links round-trip and their paint never masquerades as a font color span`() {
        val content = RichTextContent("see here", links = listOf(RichLink(4, 8, "http://example.com")))
        val restored = content.toAnnotatedString(linkColor, noFont).toRichContent()
        assertEquals(content.links, restored.links)
        // The link's own SpanStyle(color = linkColor) must not decode back as a RichStyle.FontColor span
        // (simpleStyleOf only recognizes the simple toggle styles; parameterized styles need STYLE_TAG).
        assertTrue(restored.spans.isEmpty(), restored.spans.toString())
    }

    @Test
    fun `images round-trip through the IMAGE_TAG annotation`() {
        val token = imageToken("cat.png")
        val content = RichTextContent(
            text = token,
            images = listOf(RichImage(0, token.length, "img1@libremail", "cat.png")),
        )
        val restored = content.toAnnotatedString(linkColor, noFont).toRichContent()
        assertEquals(content.images, restored.images)
    }

    @Test
    fun `alignments round-trip through paragraph styles`() {
        val content = RichTextContent("left\ncentered", alignments = listOf(RichAlignment(5, 13, RichAlign.CENTER)))
        val restored = content.toAnnotatedString(linkColor, noFont).toRichContent()
        assertEquals(content.alignments, restored.alignments)
    }

    @Test
    fun `baseStyle is position-independent so it must be threaded back in explicitly`() {
        val base = RichBaseStyle(fontCss = "Arial, sans-serif", fontSizePt = 12)
        val annotated = RichTextContent("plain", baseStyle = base).toAnnotatedString(linkColor, noFont)
        // toAnnotatedString never encodes baseStyle into the AnnotatedString itself...
        assertEquals(RichTextContent("plain"), annotated.toRichContent(baseStyle = null))
        // ...toRichContent only carries it because the caller (RichTextBodyField) passes it back in.
        assertEquals(base, annotated.toRichContent(baseStyle = base).baseStyle)
    }

    @Test
    fun `resolveFont maps a FontFamily span's css to a display font without losing the css on replay`() {
        val cursive = FontFamily.Cursive
        val content = RichTextContent("x", spans = listOf(RichSpan(0, 1, RichStyle.FontFamily("cursive-css"))))
        val annotated = content.toAnnotatedString(linkColor) { css -> cursive.takeIf { css == "cursive-css" } }
        assertEquals(cursive, annotated.spanStyles.single().item.fontFamily)
        // Resolution is display-only: the model's css string survives regardless of whether it resolved.
        assertEquals(content.spans, annotated.toRichContent().spans)
    }

    @Test
    fun `a mixed run keeps every channel (style, link, alignment, image) distinct on the round trip`() {
        val content = RichTextContent(
            text = "• item\nnote [image: dog.png]",
            spans = listOf(RichSpan(2, 6, RichStyle.Bold), RichSpan(7, 11, RichStyle.FontSize(10))),
            links = listOf(RichLink(7, 11, "http://example.com")),
            alignments = listOf(RichAlignment(0, 6, RichAlign.CENTER)),
            images = listOf(RichImage(12, 28, "dog@mail", "dog.png")),
        )
        val restored = content.toAnnotatedString(linkColor, noFont).toRichContent()
        assertEquals(content.text, restored.text)
        assertEquals(content.spans.toSet(), restored.spans.toSet())
        assertEquals(content.links, restored.links)
        assertEquals(content.alignments, restored.alignments)
        assertEquals(content.images, restored.images)
    }

    // --- applyStyle ---

    @Test
    fun `applyStyle toggles bold over the selection and keeps the selection unchanged`() {
        val value = field("hello", TextRange(0, 5))
        val bolded = applyStyle(value, RichStyle.Bold, linkColor)
        assertEquals(TextRange(0, 5), bolded.selection)
        assertEquals(listOf(RichSpan(0, 5, RichStyle.Bold)), bolded.annotatedString.toRichContent().spans)

        val plain = applyStyle(bolded, RichStyle.Bold, linkColor)
        assertTrue(plain.annotatedString.toRichContent().spans.isEmpty())
    }

    @Test
    fun `applyStyle replaces a different value of the same parameterized kind`() {
        val value = field("sized", TextRange(0, 5))
        val small = applyStyle(value, RichStyle.FontSize(12), linkColor)
        val big = applyStyle(small, RichStyle.FontSize(24), linkColor)
        assertEquals(listOf(RichSpan(0, 5, RichStyle.FontSize(24))), big.annotatedString.toRichContent().spans)
    }

    @Test
    fun `applyStyle is a no-op with a collapsed selection`() {
        val value = field("hello", TextRange(2))
        val result = applyStyle(value, RichStyle.Bold, linkColor)
        assertTrue(result.annotatedString.toRichContent().spans.isEmpty())
    }

    // --- clearStyle ---

    @Test
    fun `clearStyle removes a font color span regardless of its value`() {
        val value = field("hello", TextRange(0, 5))
        val colored = applyStyle(value, RichStyle.FontColor(0xFFFF0000.toInt()), linkColor)
        val cleared = clearStyle(colored, RichStyle.FontColor::class.java, linkColor)
        assertTrue(cleared.annotatedString.toRichContent().spans.isEmpty())
    }

    @Test
    fun `clearStyle splits a span that only partially overlaps the selection`() {
        val value = field("abcdef", TextRange(0, 6))
        val colored = applyStyle(value, RichStyle.Highlight(0xFFFFFF00.toInt()), linkColor)
        val narrowed = colored.copy(selection = TextRange(2, 4))
        val cleared = clearStyle(narrowed, RichStyle.Highlight::class.java, linkColor)
        assertEquals(
            listOf(
                RichSpan(0, 2, RichStyle.Highlight(0xFFFFFF00.toInt())),
                RichSpan(4, 6, RichStyle.Highlight(0xFFFFFF00.toInt())),
            ),
            cleared.annotatedString.toRichContent().spans,
        )
    }

    @Test
    fun `clearStyle only removes spans of the given kind, leaving other styles intact`() {
        val value = field("hi", TextRange(0, 2))
        val bolded = applyStyle(value, RichStyle.Bold, linkColor)
        val both = applyStyle(bolded, RichStyle.FontColor(0xFF000000.toInt()), linkColor)
        val cleared = clearStyle(both, RichStyle.FontColor::class.java, linkColor)
        assertEquals(listOf(RichSpan(0, 2, RichStyle.Bold)), cleared.annotatedString.toRichContent().spans)
    }

    @Test
    fun `clearStyle is a no-op with a collapsed selection`() {
        val value = field("hello", TextRange(0, 5))
        val colored = applyStyle(value, RichStyle.FontColor(0xFFFF0000.toInt()), linkColor)
        val collapsed = colored.copy(selection = TextRange(2))
        val result = clearStyle(collapsed, RichStyle.FontColor::class.java, linkColor)
        assertEquals(colored.annotatedString.toRichContent().spans, result.annotatedString.toRichContent().spans)
    }

    @Test
    fun `clearStyle removes a font size span regardless of its value`() {
        val value = field("hello", TextRange(0, 5))
        val sized = applyStyle(value, RichStyle.FontSize(18), linkColor)
        val cleared = clearStyle(sized, RichStyle.FontSize::class.java, linkColor)
        assertTrue(cleared.annotatedString.toRichContent().spans.isEmpty())
    }

    // --- applyBlock ---

    @Test
    fun `applyBlock adds a bullet at the caret and shifts the selection past it`() {
        val value = field("ab", TextRange(0))
        val result = applyBlock(value, BlockMarker.BULLET, linkColor, noFont)
        assertEquals("• ab", result.annotatedString.text)
        assertEquals(TextRange(2), result.selection)
    }

    @Test
    fun `applyBlock removes the marker on a second toggle`() {
        val value = field("ab", TextRange(0))
        val bulleted = applyBlock(value, BlockMarker.BULLET, linkColor, noFont)
        val plain = applyBlock(bulleted.copy(selection = TextRange(0)), BlockMarker.BULLET, linkColor, noFont)
        assertEquals("ab", plain.annotatedString.text)
    }

    @Test
    fun `applyBlock replaces a different marker across a multi-line selection`() {
        val value = field("• a\n• b", TextRange(0, 7))
        val result = applyBlock(value, BlockMarker.ORDERED, linkColor, noFont)
        assertEquals("1. a\n2. b", result.annotatedString.text)
    }

    @Test
    fun `applyBlock bullet on an end-of-text caret marks the line and serializes to ul li html`() {
        // The JVM-layer twin of ComposeScreenTest.formattingToolbar_bulletButtonMarksTheLineAndSendsItAsHtml:
        // a bullet tap on the end-of-text caret that typing leaves must mark the whole line and serialize
        // to a real list. Pinning it here catches a regression in the block-toggle/HTML flow without an
        // emulator; the instrumented test additionally guards that the toolbar button stays tappable.
        val value = field("Buy milk", TextRange(8))
        val bulleted = applyBlock(value, BlockMarker.BULLET, linkColor, noFont)
        val content = bulleted.annotatedString.toRichContent()
        assertEquals("• Buy milk", content.text)
        assertEquals("<ul><li>Buy milk</li></ul>", RichTextHtml.toHtml(content))
    }

    // --- applyLink ---

    @Test
    fun `applyLink annotates the selection with the url and paints it in the link color`() {
        val value = field("see here", TextRange(4, 8))
        val linked = applyLink(value, "http://example.com", linkColor, noFont)
        val content = linked.annotatedString.toRichContent()
        assertEquals(listOf(RichLink(4, 8, "http://example.com")), content.links)
        assertEquals(SpanStyle(color = linkColor), linked.annotatedString.spanStyles.single().item)
    }

    @Test
    fun `applyLink is a no-op without a selection`() {
        val value = field("see here", TextRange(4))
        val result = applyLink(value, "http://example.com", linkColor, noFont)
        assertTrue(result.annotatedString.toRichContent().links.isEmpty())
    }

    // --- applyBaseStyle ---

    @Test
    fun `applyBaseStyle overlays the message-wide font family and size`() {
        val cursive = FontFamily.Cursive
        val base = RichBaseStyle(fontCss = "cursive-css", fontSizePt = 18)
        val result = applyBaseStyle(TextStyle(fontSize = 14.sp), base) { css ->
            cursive.takeIf { css == "cursive-css" }
        }
        assertEquals(cursive, result.fontFamily)
        assertEquals(18.sp, result.fontSize)
    }

    @Test
    fun `applyBaseStyle is a no-op when there is no base style`() {
        val original = TextStyle(fontSize = 14.sp)
        assertEquals(original, applyBaseStyle(original, null, noFont))
    }

    // --- FormattingToolbar's active/inactive toggle state ---
    // FormattingToolbar lights up a button with exactly `RichTextEditing.isStyled`/`hasBlock` over the
    // field's current selection (see RichTextEditor.kt); these tests drive that same call through the
    // TextFieldValue produced by the toolbar's own editing ops, so they pin the toggle behavior a user
    // actually sees without needing to compose the toolbar itself.

    @Test
    fun `bold toggle state flips as the toolbar would read it after each tap`() {
        var value = field("hello world", TextRange(0, 5))
        assertFalse(isBoldActive(value))

        value = applyStyle(value, RichStyle.Bold, linkColor)
        assertTrue(isBoldActive(value))

        // A selection that spans past the bold run is a mixed selection - inactive, like isStyled reports.
        assertFalse(isBoldActive(value.copy(selection = TextRange(0, 11))))

        value = applyStyle(value, RichStyle.Bold, linkColor)
        assertFalse(isBoldActive(value))
    }

    @Test
    fun `bullet toggle state is active only once every touched line carries the marker`() {
        val start = field("a\nb", TextRange(0))
        assertFalse(isBulletActive(start.copy(selection = TextRange(0, 3))))

        val firstLineOnly = applyBlock(start, BlockMarker.BULLET, linkColor, noFont)
        assertEquals("• a\nb", firstLineOnly.annotatedString.text)
        val wholeText = TextRange(0, firstLineOnly.annotatedString.length)
        assertFalse(isBulletActive(firstLineOnly.copy(selection = wholeText)))

        val bothLines = applyBlock(firstLineOnly.copy(selection = wholeText), BlockMarker.BULLET, linkColor, noFont)
        assertEquals("• a\n• b", bothLines.annotatedString.text)
        val fullText = TextRange(0, bothLines.annotatedString.length)
        assertTrue(isBulletActive(bothLines.copy(selection = fullText)))
    }

    /** Mirrors exactly what FormattingToolbar reads to decide a style button's active/inactive tint. */
    private fun isBoldActive(value: TextFieldValue): Boolean = RichTextEditing.isStyled(
        value.annotatedString.toRichContent(),
        value.selection.min,
        value.selection.max,
        RichStyle.Bold,
    )

    /** Mirrors exactly what FormattingToolbar reads to decide a block button's active/inactive tint. */
    private fun isBulletActive(value: TextFieldValue): Boolean = RichTextEditing.hasBlock(
        value.annotatedString.toRichContent(),
        value.selection.min,
        value.selection.max,
        BlockMarker.BULLET,
    )
}
