// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.richtext

/** A block-level marker the toolbar can toggle over the selected lines. */
enum class BlockMarker { BULLET, ORDERED, QUOTE }

/** The result of an editing op: the new [content] and where the selection should land. */
data class EditResult(val content: RichTextContent, val selectionStart: Int, val selectionEnd: Int)

/**
 * Pure editing operations over [RichTextContent] — toggling inline styles/links and block markers —
 * shared by the Compose editor's toolbar. Keeping them free of Compose types makes the tricky index
 * bookkeeping (splitting spans, shifting offsets when a line marker is inserted/removed) unit-testable
 * on the JVM.
 */
object RichTextEditing {

    /** Adds [style] over [[start], [end]) if it is not already fully styled, otherwise removes it. */
    fun toggleStyle(content: RichTextContent, start: Int, end: Int, style: RichStyle): RichTextContent {
        if (start >= end) return content
        val others = content.spans.filter { it.style != style }
        val same = content.spans.filter { it.style == style }
        val updated = if (isFullyStyled(same, start, end)) {
            subtractRange(same, start, end)
        } else {
            mergeSameStyle(same + RichSpan(start, end, style))
        }
        return content.copy(spans = (others + updated).sortedBy { it.start })
    }

    /** Links [[start], [end]) to [url], replacing any links that overlap the range. */
    fun applyLink(content: RichTextContent, start: Int, end: Int, url: String): RichTextContent {
        if (start >= end || url.isBlank()) return content
        val kept = content.links.filter { it.end <= start || it.start >= end }
        return content.copy(links = (kept + RichLink(start, end, url)).sortedBy { it.start })
    }

    /** Removes any links overlapping [[start], [end]). */
    fun removeLink(content: RichTextContent, start: Int, end: Int): RichTextContent {
        if (start >= end) return content
        return content.copy(links = content.links.filter { it.end <= start || it.start >= end })
    }

    /** Whether [[start], [end]) is entirely covered by [style] (drives the toolbar's toggle state). */
    fun isStyled(content: RichTextContent, start: Int, end: Int, style: RichStyle): Boolean =
        start < end && isFullyStyled(content.spans.filter { it.style == style }, start, end)

    /** Whether every line the selection touches carries [marker]. */
    fun hasBlock(content: RichTextContent, start: Int, end: Int, marker: BlockMarker): Boolean {
        val lineStarts = lineStartsTouching(content.text, start, end)
        return lineStarts.isNotEmpty() && lineStarts.all { markerAt(content.text, it) == marker }
    }

    /**
     * Toggles [marker] across every line the selection touches: if all those lines already carry it,
     * it is removed; otherwise it is applied (replacing any other block marker already there). Spans,
     * links and the selection are shifted to track the inserted/removed prefixes.
     */
    fun toggleBlock(content: RichTextContent, start: Int, end: Int, marker: BlockMarker): EditResult {
        val text = content.text
        val lineStarts = lineStartsTouching(text, start, end)
        val allHaveMarker = lineStarts.all { markerAt(text, it) == marker }
        val edits = ArrayList<LineEdit>()
        var ordinal = 1
        for (lineStart in lineStarts) {
            val existing = markerLengthAt(text, lineStart)
            when {
                allHaveMarker && existing > 0 -> edits.add(LineEdit(lineStart, existing, ""))
                allHaveMarker -> Unit
                else -> edits.add(LineEdit(lineStart, existing, insertFor(marker, ordinal++)))
            }
        }
        val (newText, remap) = applyEdits(text, edits)
        val newSpans = content.spans.mapNotNull { remapSpan(it, remap) }
        val newLinks = content.links.mapNotNull { remapLink(it, remap) }
        return EditResult(RichTextContent(newText, newSpans, newLinks), remap(start), remap(end))
    }
}

private fun insertFor(marker: BlockMarker, ordinal: Int): String = when (marker) {
    BlockMarker.BULLET -> BULLET_PREFIX
    BlockMarker.QUOTE -> QUOTE_PREFIX
    BlockMarker.ORDERED -> "$ordinal. "
}

private fun remapSpan(span: RichSpan, remap: (Int) -> Int): RichSpan? {
    val s = remap(span.start)
    val e = remap(span.end)
    return if (s < e) RichSpan(s, e, span.style) else null
}

private fun remapLink(link: RichLink, remap: (Int) -> Int): RichLink? {
    val s = remap(link.start)
    val e = remap(link.end)
    return if (s < e) RichLink(s, e, link.url) else null
}

// --- inline style helpers ---

private fun isFullyStyled(spans: List<RichSpan>, start: Int, end: Int): Boolean {
    var pos = start
    for (span in spans.filter { it.end > start && it.start < end }.sortedBy { it.start }) {
        if (span.start > pos) return false
        pos = maxOf(pos, span.end)
        if (pos >= end) return true
    }
    return pos >= end
}

private fun subtractRange(spans: List<RichSpan>, start: Int, end: Int): List<RichSpan> = spans.flatMap { span ->
    when {
        span.end <= start || span.start >= end -> listOf(span)
        else -> buildList {
            if (span.start < start) add(span.copy(end = start))
            if (span.end > end) add(span.copy(start = end))
        }
    }
}

private fun mergeSameStyle(spans: List<RichSpan>): List<RichSpan> {
    val merged = ArrayList<RichSpan>()
    for (span in spans.sortedBy { it.start }) {
        val last = merged.lastOrNull()
        if (last != null && span.start <= last.end) {
            merged[merged.size - 1] = last.copy(end = maxOf(last.end, span.end))
        } else {
            merged.add(span)
        }
    }
    return merged
}

// --- block marker helpers ---

private val ORDERED = Regex("^\\d+\\. ")

private fun markerAt(text: String, lineStart: Int): BlockMarker? {
    val rest = text.substring(lineStart)
    return when {
        rest.startsWith(BULLET_PREFIX) -> BlockMarker.BULLET
        rest.startsWith(QUOTE_PREFIX) -> BlockMarker.QUOTE
        // ORDERED is anchored at ^, so find() matches only when this line starts with "N. ".
        ORDERED.find(rest) != null -> BlockMarker.ORDERED
        else -> null
    }
}

private fun markerLengthAt(text: String, lineStart: Int): Int {
    val rest = text.substring(lineStart)
    return when {
        rest.startsWith(BULLET_PREFIX) -> BULLET_PREFIX.length
        rest.startsWith(QUOTE_PREFIX) -> QUOTE_PREFIX.length
        else -> ORDERED.find(rest)?.value?.length ?: 0
    }
}

/** Start offsets of every line the range [[start], [end]] intersects (a caret counts as its line). */
private fun lineStartsTouching(text: String, start: Int, end: Int): List<Int> {
    val from = start.coerceIn(0, text.length)
    val to = end.coerceIn(from, text.length)
    val result = ArrayList<Int>()
    var lineStart = if (from == 0) 0 else text.lastIndexOf('\n', from - 1).let { if (it == -1) 0 else it + 1 }
    while (lineStart <= text.length) {
        result.add(lineStart)
        val nl = text.indexOf('\n', lineStart)
        if (nl == -1 || nl >= to) break
        lineStart = nl + 1
    }
    return result
}

private data class LineEdit(val offset: Int, val deleteLen: Int, val insert: String)

/** Applies line-start [edits] (ascending, non-overlapping) and returns the new text + an index remap. */
private fun applyEdits(text: String, edits: List<LineEdit>): Pair<String, (Int) -> Int> {
    if (edits.isEmpty()) return text to { it }
    val sorted = edits.sortedBy { it.offset }
    val sb = StringBuilder()
    var cursor = 0
    for (edit in sorted) {
        sb.append(text, cursor, edit.offset)
        sb.append(edit.insert)
        cursor = edit.offset + edit.deleteLen
    }
    sb.append(text, cursor, text.length)
    val remap: (Int) -> Int = { index -> remapIndex(index, sorted).coerceIn(0, sb.length) }
    return sb.toString() to remap
}

private fun remapIndex(index: Int, edits: List<LineEdit>): Int {
    var delta = 0
    for (edit in edits) {
        val delEnd = edit.offset + edit.deleteLen
        when {
            delEnd <= index -> delta += edit.insert.length - edit.deleteLen
            edit.offset < index -> delta += edit.insert.length - (index - edit.offset)
        }
    }
    return index + delta
}
