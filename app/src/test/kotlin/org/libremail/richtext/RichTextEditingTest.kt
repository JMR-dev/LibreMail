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

    // --- setAlignment / alignmentAt ---

    @Test
    fun `setAlignment centers the caret's paragraph and start clears it back to default`() {
        val centered = RichTextEditing.setAlignment(RichTextContent("hello"), 2, 2, RichAlign.CENTER)
        assertEquals(listOf(RichAlignment(0, 5, RichAlign.CENTER)), centered.alignments)

        // START is the writing-direction default, so it is stored as "no alignment" (range dropped).
        val cleared = RichTextEditing.setAlignment(centered, 0, 5, RichAlign.START)
        assertTrue(cleared.alignments.isEmpty())
    }

    @Test
    fun `setAlignment over a multi-paragraph selection makes one merged range`() {
        val result = RichTextEditing.setAlignment(RichTextContent("a\nb\nc"), 0, 5, RichAlign.END)
        assertEquals(listOf(RichAlignment(0, 5, RichAlign.END)), result.alignments)
    }

    @Test
    fun `setAlignment on a middle paragraph splits an existing block`() {
        val base = RichTextContent("a\nb\nc", alignments = listOf(RichAlignment(0, 5, RichAlign.CENTER)))
        // Select only the "b" line (positions 2..3) and right-align it.
        val result = RichTextEditing.setAlignment(base, 2, 3, RichAlign.END)
        assertEquals(
            listOf(
                RichAlignment(0, 1, RichAlign.CENTER),
                RichAlignment(2, 3, RichAlign.END),
                RichAlignment(4, 5, RichAlign.CENTER),
            ),
            result.alignments,
        )
    }

    @Test
    fun `setAlignment leaves paragraphs the selection does not touch alone`() {
        val base = RichTextContent("a\nb", alignments = listOf(RichAlignment(2, 3, RichAlign.END)))
        val result = RichTextEditing.setAlignment(base, 0, 1, RichAlign.CENTER)
        assertEquals(
            listOf(RichAlignment(0, 1, RichAlign.CENTER), RichAlignment(2, 3, RichAlign.END)),
            result.alignments,
        )
    }

    @Test
    fun `setAlignment across a blank line does not anchor alignment to the blank paragraph`() {
        // The blank middle paragraph cannot carry a text-align in the HTML model, so it breaks the run
        // into two ranges — the canonical form RichTextHtml.fromHtml also returns.
        val result = RichTextEditing.setAlignment(RichTextContent("a\n\nb"), 0, 4, RichAlign.CENTER)
        assertEquals(
            listOf(RichAlignment(0, 1, RichAlign.CENTER), RichAlignment(3, 4, RichAlign.CENTER)),
            result.alignments,
        )
    }

    @Test
    fun `setAlignment on an empty document is a no-op`() {
        assertTrue(RichTextEditing.setAlignment(RichTextContent(""), 0, 0, RichAlign.CENTER).alignments.isEmpty())
    }

    @Test
    fun `setAlignment output round-trips through html unchanged`() {
        listOf(
            RichTextEditing.setAlignment(RichTextContent("a\nb\nc"), 0, 5, RichAlign.END),
            RichTextEditing.setAlignment(RichTextContent("a\n\nb"), 0, 4, RichAlign.CENTER),
            RichTextEditing.setAlignment(
                RichTextContent("a\nb\nc", alignments = listOf(RichAlignment(0, 5, RichAlign.CENTER))),
                2,
                3,
                RichAlign.END,
            ),
        ).forEach { content ->
            val restored = RichTextHtml.fromHtml(RichTextHtml.toHtml(content))
            assertEquals(content.text, restored.text, "text of $content")
            assertEquals(content.alignments, restored.alignments, "alignments of $content")
        }
    }

    @Test
    fun `alignmentAt reports the shared alignment, START default, or null when mixed`() {
        val content = RichTextContent(
            "a\nb\nc",
            alignments = listOf(RichAlignment(0, 1, RichAlign.CENTER), RichAlignment(2, 3, RichAlign.END)),
        )
        assertEquals(RichAlign.CENTER, RichTextEditing.alignmentAt(content, 0, 1))
        assertEquals(RichAlign.END, RichTextEditing.alignmentAt(content, 2, 3))
        // "c" carries no explicit alignment, so it reads as the START default.
        assertEquals(RichAlign.START, RichTextEditing.alignmentAt(content, 4, 5))
        // A selection spanning center + end paragraphs is mixed.
        assertNull(RichTextEditing.alignmentAt(content, 0, 3))
    }

    @Test
    fun `alignmentAt treats a plain paragraph as START`() {
        assertEquals(RichAlign.START, RichTextEditing.alignmentAt(RichTextContent("plain"), 0, 5))
    }

    // --- insertImage ---

    @Test
    fun `insertImage drops a token and RichImage at the caret and shifts later spans`() {
        val base = RichTextContent("ab", spans = listOf(RichSpan(1, 2, RichStyle.Bold)))
        val result = RichTextEditing.insertImage(base, 1, "cid1@x", "pic")
        val token = imageToken("pic")
        assertEquals("a${token}b", result.content.text)
        assertEquals(listOf(RichImage(1, 1 + token.length, "cid1@x", "pic")), result.content.images)
        // The bold run that began at the caret shifts entirely past the inserted token.
        assertEquals(listOf(RichSpan(1 + token.length, 2 + token.length, RichStyle.Bold)), result.content.spans)
        assertEquals(1 + token.length, result.selectionStart)
        assertEquals(1 + token.length, result.selectionEnd)
    }

    @Test
    fun `insertImage output round-trips its image through html`() {
        val result = RichTextEditing.insertImage(RichTextContent("hi"), 2, "c@x", "cat.png")
        val restored = RichTextHtml.fromHtml(RichTextHtml.toHtml(result.content))
        assertEquals(result.content.text, restored.text)
        assertEquals(result.content.images, restored.images)
    }

    @Test
    fun `insertImage leaves runs entirely before the insertion point unshifted`() {
        val base = RichTextContent(
            "abc",
            spans = listOf(RichSpan(0, 1, RichStyle.Bold)),
            links = listOf(RichLink(0, 1, "http://x")),
            alignments = listOf(RichAlignment(0, 1, RichAlign.CENTER)),
        )
        val result = RichTextEditing.insertImage(base, 2, "cid", "n")
        // pos = 2: a span/link/alignment ending at or before it keeps its original offsets.
        assertEquals(RichSpan(0, 1, RichStyle.Bold), result.content.spans.single())
        assertEquals(RichLink(0, 1, "http://x"), result.content.links.single())
        assertEquals(RichAlignment(0, 1, RichAlign.CENTER), result.content.alignments.single())
    }

    // --- removeLink ---

    @Test
    fun `removeLink is a no-op for an empty or inverted range`() {
        val linked = RichTextContent("hello", links = listOf(RichLink(0, 5, "http://x")))
        assertEquals(linked.links, RichTextEditing.removeLink(linked, 3, 3).links)
        assertEquals(linked.links, RichTextEditing.removeLink(linked, 4, 2).links)
    }

    @Test
    fun `removeLink drops only the links overlapping the range`() {
        val content = RichTextContent(
            "0123456789",
            links = listOf(RichLink(0, 2, "a"), RichLink(3, 6, "b"), RichLink(7, 9, "c")),
        )
        // Over [4,7): "a" ends before it, "c" starts at/after its end — both kept; "b" overlaps and goes.
        val result = RichTextEditing.removeLink(content, 4, 7)
        assertEquals(listOf(RichLink(0, 2, "a"), RichLink(7, 9, "c")), result.links)
    }

    // --- applyLink edge cases ---

    @Test
    fun `applyLink ignores a blank url or an empty range`() {
        val base = RichTextContent("hello", links = listOf(RichLink(0, 5, "http://x")))
        assertEquals(base, RichTextEditing.applyLink(base, 1, 3, "   "))
        assertEquals(base, RichTextEditing.applyLink(base, 2, 2, "http://y"))
    }

    @Test
    fun `applyLink keeps links outside the range and splits partial overlaps, keeping the remainder`() {
        val content = RichTextContent(
            "0123456789",
            links = listOf(RichLink(0, 2, "a"), RichLink(3, 6, "b"), RichLink(7, 9, "c")),
        )
        val result = RichTextEditing.applyLink(content, 4, 7, "http://new")
        assertEquals(
            // "a" is wholly outside; "b" (3,6) overlaps [4,7) so only its (3,4) remainder survives (it is
            // no longer dropped whole); the new link takes [4,7); "c" starts at the range end, kept whole.
            listOf(RichLink(0, 2, "a"), RichLink(3, 4, "b"), RichLink(4, 7, "http://new"), RichLink(7, 9, "c")),
            result.links.sortedBy { it.start },
        )
    }

    @Test
    fun `applyLink over the middle of a link relinks the middle and keeps both surrounding remainders`() {
        val content = RichTextContent("0123456789", links = listOf(RichLink(0, 8, "old")))
        val result = RichTextEditing.applyLink(content, 3, 5, "new")
        assertEquals(
            listOf(RichLink(0, 3, "old"), RichLink(3, 5, "new"), RichLink(5, 8, "old")),
            result.links.sortedBy { it.start },
        )
    }

    // --- mergeSameValueSpans (shared merge used by toggleStyle and the HTML parser) ---

    @Test
    fun `mergeSameValueSpans coalesces touching and overlapping runs of the same value only`() {
        val merged = mergeSameValueSpans(
            listOf(
                RichSpan(5, 8, RichStyle.Bold), // out of order, and overlaps the (3,6) run below
                RichSpan(0, 3, RichStyle.Bold),
                RichSpan(3, 6, RichStyle.Bold), // touches (0,3) and overlaps (5,8) → one 0..8 run
                RichSpan(0, 4, RichStyle.Italic), // a different value never folds into the Bold run
                RichSpan(10, 12, RichStyle.Bold), // a gap breaks the run into a fresh one
            ),
        )
        assertEquals(
            listOf(
                RichSpan(0, 8, RichStyle.Bold),
                RichSpan(0, 4, RichStyle.Italic),
                RichSpan(10, 12, RichStyle.Bold),
            ),
            merged,
        )
    }

    // --- styleAt / isStyled caret edges ---

    @Test
    fun `styleAt at a caret finds the run it sits in, and null outside every run`() {
        val content = RichTextContent("abcd", spans = listOf(RichSpan(1, 3, RichStyle.FontSize(12))))
        assertEquals(RichStyle.FontSize(12), RichTextEditing.styleAt<RichStyle.FontSize>(content, 2, 2))
        // Caret before every run: no candidate starts at/before it.
        assertNull(RichTextEditing.styleAt<RichStyle.FontSize>(content, 0, 0))
        // Caret past every run: the candidate starts before it but ends before it too.
        assertNull(RichTextEditing.styleAt<RichStyle.FontSize>(content, 4, 4))
    }

    @Test
    fun `isStyled is false for a caret (empty range)`() {
        val content = RichTextContent("ab", spans = listOf(RichSpan(0, 2, RichStyle.Bold)))
        assertFalse(RichTextEditing.isStyled(content, 1, 1, RichStyle.Bold))
    }

    @Test
    fun `isStyled is false when the style covers only part of the range`() {
        // The span runs out before the end of the query, so the fully-styled check falls through false.
        val content = RichTextContent("abcd", spans = listOf(RichSpan(0, 2, RichStyle.Bold)))
        assertFalse(RichTextEditing.isStyled(content, 0, 4, RichStyle.Bold))
    }

    // --- toggleStyle: same-kind runs outside the selection ---

    @Test
    fun `toggleStyle leaves same-kind runs entirely outside the selection untouched`() {
        val base = RichTextContent(
            "abcdef",
            spans = listOf(RichSpan(0, 1, RichStyle.FontColor(1)), RichSpan(5, 6, RichStyle.FontColor(1))),
        )
        val result = RichTextEditing.toggleStyle(base, 2, 4, RichStyle.FontColor(2))
        assertEquals(
            setOf(
                RichSpan(0, 1, RichStyle.FontColor(1)),
                RichSpan(2, 4, RichStyle.FontColor(2)),
                RichSpan(5, 6, RichStyle.FontColor(1)),
            ),
            result.spans.toSet(),
        )
    }

    // --- block markers: quote / ordered detection and removal ---

    @Test
    fun `hasBlock detects quote and ordered markers and rejects a mixed selection`() {
        assertTrue(RichTextEditing.hasBlock(RichTextContent("> a\n> b"), 0, 7, BlockMarker.QUOTE))
        assertTrue(RichTextEditing.hasBlock(RichTextContent("1. a\n2. b"), 0, 9, BlockMarker.ORDERED))
        // One line lacks the marker, so `all { … }` short-circuits to false.
        assertFalse(RichTextEditing.hasBlock(RichTextContent("• a\nplain"), 0, 9, BlockMarker.BULLET))
    }

    @Test
    fun `toggleBlock removes quote and ordered markers, measuring each prefix length`() {
        val quote = RichTextEditing.toggleBlock(RichTextContent("> a\n> b"), 0, 7, BlockMarker.QUOTE)
        assertEquals("a\nb", quote.content.text)
        val ordered = RichTextEditing.toggleBlock(RichTextContent("1. a\n2. b"), 0, 9, BlockMarker.ORDERED)
        assertEquals("a\nb", ordered.content.text)
    }

    @Test
    fun `toggleBlock adding a marker shifts a link past the inserted prefix`() {
        val base = RichTextContent("x", links = listOf(RichLink(0, 1, "http://x")))
        val result = RichTextEditing.toggleBlock(base, 0, 0, BlockMarker.BULLET)
        assertEquals("• x", result.content.text)
        assertEquals(listOf(RichLink(2, 3, "http://x")), result.content.links)
    }

    @Test
    fun `toggleBlock removing a marker drops channels that lived entirely in the marker prefix`() {
        // Every channel covers just the "• " prefix (0,2); removing the bullet deletes it, so each
        // maps to an empty range and is dropped (the remap* helpers' null branch).
        val base = RichTextContent(
            text = "• x",
            spans = listOf(RichSpan(0, 2, RichStyle.Bold)),
            links = listOf(RichLink(0, 2, "http://x")),
            alignments = listOf(RichAlignment(0, 2, RichAlign.CENTER)),
            images = listOf(RichImage(0, 2, "c", "n")),
        )
        val result = RichTextEditing.toggleBlock(base, 0, base.text.length, BlockMarker.BULLET)
        assertEquals("x", result.content.text)
        assertTrue(result.content.spans.isEmpty())
        assertTrue(result.content.links.isEmpty())
        assertTrue(result.content.alignments.isEmpty())
        assertTrue(result.content.images.isEmpty())
    }
}
