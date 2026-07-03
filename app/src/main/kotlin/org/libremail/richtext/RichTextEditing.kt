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

    /**
     * Toggles [style] over [[start], [end]): if the exact style already covers the whole range it is
     * removed; otherwise it is applied, replacing any other value of the same kind over the range
     * (e.g. picking a new [RichStyle.FontSize] replaces the old size instead of stacking a second one).
     */
    fun toggleStyle(content: RichTextContent, start: Int, end: Int, style: RichStyle): RichTextContent {
        if (start >= end) return content
        val otherKinds = content.spans.filter { it.style::class != style::class }
        val sameKind = content.spans.filter { it.style::class == style::class }
        val cleared = subtractRange(sameKind, start, end)
        val updated = if (isFullyStyled(sameKind.filter { it.style == style }, start, end)) {
            cleared
        } else {
            mergeSameValueSpans(cleared + RichSpan(start, end, style))
        }
        return content.copy(spans = (otherKinds + updated).sortedBy { it.start })
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

    /**
     * The single value of style kind [T] over the selection, or null when absent or mixed — one call
     * gives a picker its "current value". For a caret the boundaries count as inside, so the query
     * matches the run the user is typing at the end of; at a boundary between two runs the earlier
     * run wins.
     */
    inline fun <reified T : RichStyle> styleAt(content: RichTextContent, start: Int, end: Int): T? =
        styleAt(content, start, end, T::class.java)

    /** Non-reified form of [styleAt] for callers that carry the kind as a [Class]. */
    fun <T : RichStyle> styleAt(content: RichTextContent, start: Int, end: Int, kind: Class<T>): T? {
        val candidates = content.spans.filter { kind.isInstance(it.style) }
        val value = if (start >= end) {
            candidates.firstOrNull { it.start <= start && start <= it.end }?.style
        } else {
            candidates.map { it.style }.distinct()
                .firstOrNull { v -> isFullyStyled(candidates.filter { it.style == v }, start, end) }
        }
        return kind.cast(value)
    }

    /** Whether every line the selection touches carries [marker]. */
    fun hasBlock(content: RichTextContent, start: Int, end: Int, marker: BlockMarker): Boolean {
        val lineStarts = lineStartsTouching(content.text, start, end)
        return lineStarts.isNotEmpty() && lineStarts.all { markerAt(content.text, it) == marker }
    }

    /**
     * Toggles [marker] across every line the selection touches: if all those lines already carry it,
     * it is removed; otherwise it is applied (replacing any other block marker already there). Spans,
     * links, alignments, images and the selection are shifted to track the inserted/removed prefixes.
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
        val updated = content.copy(
            text = newText,
            spans = content.spans.mapNotNull { remapSpan(it, remap) },
            links = content.links.mapNotNull { remapLink(it, remap) },
            alignments = content.alignments.mapNotNull { remapAlignment(it, remap) },
            images = content.images.mapNotNull { remapImage(it, remap) },
        )
        return EditResult(updated, remap(start), remap(end))
    }

    /**
     * Sets paragraph [align] on every line the selection [[start], [end]] touches, replacing whatever
     * alignment those lines carried and leaving untouched paragraphs alone. [RichAlign.START] is the
     * writing-direction default, so it is stored as *no* alignment (the range is dropped) — keeping an
     * otherwise-plain paragraph plaintext-only — while CENTER and END become explicit ranges. The
     * result is in the same canonical form [RichTextHtml.fromHtml] returns (one merged range per run
     * of adjacent same-aligned lines; blank paragraphs never anchor a range, since the HTML model
     * cannot pin a `text-align` to an empty `<p>`), so the model, its HTML, and the editor's
     * [ParagraphStyle] rendering never drift.
     */
    fun setAlignment(content: RichTextContent, start: Int, end: Int, align: RichAlign): RichTextContent {
        val touched = lineStartsTouching(content.text, start, end).toHashSet()
        val perLine = lineRanges(content.text).map { line ->
            val effective = if (line.start in touched) {
                align.takeUnless { it == RichAlign.START }
            } else {
                alignCovering(content.alignments, line)
            }
            line to effective
        }
        return content.copy(alignments = canonicalAlignments(perLine))
    }

    /**
     * The single alignment shared by every paragraph the selection touches, or null when they are
     * mixed. Unaligned paragraphs read as [RichAlign.START] (the default), so this drives the toolbar's
     * three-state start/center/end control directly (null lights up none of the three).
     */
    fun alignmentAt(content: RichTextContent, start: Int, end: Int): RichAlign? {
        val aligns = lineStartsTouching(content.text, start, end).map { lineStart ->
            alignCovering(content.alignments, lineRangeAt(content.text, lineStart)) ?: RichAlign.START
        }
        return aligns.distinct().singleOrNull()
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

private fun remapAlignment(alignment: RichAlignment, remap: (Int) -> Int): RichAlignment? {
    val s = remap(alignment.start)
    val e = remap(alignment.end)
    return if (s < e) alignment.copy(start = s, end = e) else null
}

private fun remapImage(image: RichImage, remap: (Int) -> Int): RichImage? {
    val s = remap(image.start)
    val e = remap(image.end)
    return if (s < e) image.copy(start = s, end = e) else null
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

// --- paragraph alignment helpers ---

/** One line's half-open range [[start], [contentEnd]) of plain text (marker included, newline excluded). */
private data class LineRange(val start: Int, val contentEnd: Int)

private fun lineRanges(text: String): List<LineRange> {
    val result = ArrayList<LineRange>()
    var lineStart = 0
    while (true) {
        val nl = text.indexOf('\n', lineStart)
        result.add(LineRange(lineStart, if (nl == -1) text.length else nl))
        if (nl == -1) break
        lineStart = nl + 1
    }
    return result
}

private fun lineRangeAt(text: String, lineStart: Int): LineRange {
    val nl = text.indexOf('\n', lineStart)
    return LineRange(lineStart, if (nl == -1) text.length else nl)
}

/**
 * The alignment covering [line], mirroring how [RichTextHtml] picks a line's alignment on emit. An
 * empty line gets a one-char probe so a range that spans it is still detected.
 */
private fun alignCovering(alignments: List<RichAlignment>, line: LineRange): RichAlign? =
    alignments.firstOrNull { it.start < maxOf(line.contentEnd, line.start + 1) && it.end > line.start }?.align

/**
 * Rebuilds canonical alignment ranges from a per-line alignment: one merged range per run of adjacent
 * non-empty same-aligned lines. Empty paragraphs cannot carry a `text-align` in the HTML model, so
 * they anchor no range and break a run — the exact form [RichTextHtml.fromHtml] returns.
 */
private fun canonicalAlignments(perLine: List<Pair<LineRange, RichAlign?>>): List<RichAlignment> {
    val result = ArrayList<RichAlignment>()
    var i = 0
    while (i < perLine.size) {
        val (line, align) = perLine[i]
        if (align == null || line.start >= line.contentEnd) {
            i++
            continue
        }
        var j = i
        while (j + 1 < perLine.size && perLine[j + 1].second == align && perLine[j + 1].first.isNotEmpty()) {
            j++
        }
        result.add(RichAlignment(line.start, perLine[j].first.contentEnd, align))
        i = j + 1
    }
    return result
}

private fun LineRange.isNotEmpty(): Boolean = start < contentEnd

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
