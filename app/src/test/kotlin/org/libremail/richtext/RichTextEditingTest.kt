// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.richtext

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RichTextEditingTest {

    @Test
    fun `toggleStyle adds then removes a style over the selection`() {
        val base = RichTextContent("hello")
        val bold = RichTextEditing.toggleStyle(base, 0, 5, RichStyle.BOLD)
        assertEquals(listOf(RichSpan(0, 5, RichStyle.BOLD)), bold.spans)

        val plain = RichTextEditing.toggleStyle(bold, 0, 5, RichStyle.BOLD)
        assertTrue(plain.spans.isEmpty())
    }

    @Test
    fun `toggleStyle over a fully styled sub-range removes just that part`() {
        val bold = RichTextContent("hello", spans = listOf(RichSpan(0, 5, RichStyle.BOLD)))
        val result = RichTextEditing.toggleStyle(bold, 1, 3, RichStyle.BOLD)
        assertEquals(
            listOf(RichSpan(0, 1, RichStyle.BOLD), RichSpan(3, 5, RichStyle.BOLD)),
            result.spans.sortedBy { it.start },
        )
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
        val base = RichTextContent("ab", spans = listOf(RichSpan(0, 2, RichStyle.BOLD)))
        val result = RichTextEditing.toggleBlock(base, 0, 0, BlockMarker.BULLET)
        assertEquals("• ab", result.content.text)
        assertEquals(listOf(RichSpan(2, 4, RichStyle.BOLD)), result.content.spans)
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
}
