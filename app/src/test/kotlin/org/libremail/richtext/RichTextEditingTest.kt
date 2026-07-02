// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.richtext

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RichTextEditingTest {

    @Test
    fun `toggleStyle adds then removes a style over the selection`() {
        val base = RichTextContent("hello")
        val bold = RichTextEditing.toggleStyle(base, 0, 5, RichStyle.Bold)
        assertEquals(listOf(RichSpan(0, 5, RichStyle.Bold)), bold.spans)

        val plain = RichTextEditing.toggleStyle(bold, 0, 5, RichStyle.Bold)
        assertTrue(plain.spans.isEmpty())
    }

    @Test
    fun `toggleStyle over a fully styled sub-range removes just that part`() {
        val bold = RichTextContent("hello", spans = listOf(RichSpan(0, 5, RichStyle.Bold)))
        val result = RichTextEditing.toggleStyle(bold, 1, 3, RichStyle.Bold)
        assertEquals(
            listOf(RichSpan(0, 1, RichStyle.Bold), RichSpan(3, 5, RichStyle.Bold)),
            result.spans.sortedBy { it.start },
        )
    }

    @Test
    fun `toggleStyle with a different value of the same kind replaces it`() {
        val base = RichTextContent("hello", spans = listOf(RichSpan(0, 5, RichStyle.FontSize(12))))
        val resized = RichTextEditing.toggleStyle(base, 0, 5, RichStyle.FontSize(18))
        assertEquals(listOf(RichSpan(0, 5, RichStyle.FontSize(18))), resized.spans)

        val removed = RichTextEditing.toggleStyle(resized, 0, 5, RichStyle.FontSize(18))
        assertTrue(removed.spans.isEmpty())
    }

    @Test
    fun `toggleStyle replaces only the selected part of an old value`() {
        val base = RichTextContent("abcdef", spans = listOf(RichSpan(0, 6, RichStyle.FontColor(1))))
        val result = RichTextEditing.toggleStyle(base, 2, 4, RichStyle.FontColor(2))
        assertEquals(
            listOf(
                RichSpan(0, 2, RichStyle.FontColor(1)),
                RichSpan(2, 4, RichStyle.FontColor(2)),
                RichSpan(4, 6, RichStyle.FontColor(1)),
            ),
            result.spans,
        )
    }

    @Test
    fun `distinct style kinds toggle independently`() {
        val base = RichTextContent("hello")
        val styled = RichTextEditing.toggleStyle(
            RichTextEditing.toggleStyle(base, 0, 5, RichStyle.Bold),
            0,
            5,
            RichStyle.Strikethrough,
        )
        assertEquals(
            setOf(RichSpan(0, 5, RichStyle.Bold), RichSpan(0, 5, RichStyle.Strikethrough)),
            styled.spans.toSet(),
        )
        assertTrue(RichTextEditing.isStyled(styled, 0, 5, RichStyle.Strikethrough))
        assertTrue(RichTextEditing.isStyled(styled, 0, 5, RichStyle.Bold))
    }

    @Test
    fun `styleAt reports the selection's single value or null when mixed`() {
        val content = RichTextContent(
            "abcd",
            spans = listOf(RichSpan(0, 2, RichStyle.FontSize(10)), RichSpan(2, 4, RichStyle.FontSize(14))),
        )
        assertEquals(RichStyle.FontSize(10), RichTextEditing.styleAt<RichStyle.FontSize>(content, 0, 2))
        assertNull(RichTextEditing.styleAt<RichStyle.FontSize>(content, 0, 4))
        assertEquals(RichStyle.FontSize(14), RichTextEditing.styleAt<RichStyle.FontSize>(content, 3, 3))
        assertNull(RichTextEditing.styleAt<RichStyle.FontColor>(content, 0, 2))
    }

    @Test
    fun `applyLink links the selection and removes overlapping links`() {
        val base = RichTextContent("see here")
        val linked = RichTextEditing.applyLink(base, 4, 8, "http://x")
        assertEquals(listOf(RichLink(4, 8, "http://x")), linked.links)

        val relinked = RichTextEditing.applyLink(linked, 0, 8, "http://y")
        assertEquals(listOf(RichLink(0, 8, "http://y")), relinked.links)
    }

    @Test
    fun `toggleBlock adds a bullet marker to the caret's line and shifts spans`() {
        val base = RichTextContent("ab", spans = listOf(RichSpan(0, 2, RichStyle.Bold)))
        val result = RichTextEditing.toggleBlock(base, 0, 0, BlockMarker.BULLET)
        assertEquals("• ab", result.content.text)
        assertEquals(listOf(RichSpan(2, 4, RichStyle.Bold)), result.content.spans)
    }

    @Test
    fun `toggleBlock numbers each line of a multi-line selection`() {
        val base = RichTextContent("a\nb\nc")
        val result = RichTextEditing.toggleBlock(base, 0, 5, BlockMarker.ORDERED)
        assertEquals("1. a\n2. b\n3. c", result.content.text)
    }

    @Test
    fun `toggleBlock removes the marker when every selected line already has it`() {
        val base = RichTextContent("• a\n• b")
        val result = RichTextEditing.toggleBlock(base, 0, base.text.length, BlockMarker.BULLET)
        assertEquals("a\nb", result.content.text)
        assertFalse(RichTextEditing.hasBlock(result.content, 0, result.content.text.length, BlockMarker.BULLET))
    }

    @Test
    fun `toggleBlock replaces a different marker in place`() {
        val base = RichTextContent("• a")
        val result = RichTextEditing.toggleBlock(base, 0, 3, BlockMarker.QUOTE)
        assertEquals("> a", result.content.text)
    }

    @Test
    fun `toggleBlock keeps and remaps the alignment image and base-style channels`() {
        val base = RichTextContent(
            text = "hi [image: x]",
            alignments = listOf(RichAlignment(0, 13, RichAlign.CENTER)),
            images = listOf(RichImage(3, 13, "c1", "x")),
            baseStyle = RichBaseStyle(fontSizePt = 12),
        )
        val result = RichTextEditing.toggleBlock(base, 0, 0, BlockMarker.BULLET)
        assertEquals("• hi [image: x]", result.content.text)
        assertEquals(listOf(RichAlignment(2, 15, RichAlign.CENTER)), result.content.alignments)
        assertEquals(listOf(RichImage(5, 15, "c1", "x")), result.content.images)
        assertEquals(base.baseStyle, result.content.baseStyle)
    }
}
