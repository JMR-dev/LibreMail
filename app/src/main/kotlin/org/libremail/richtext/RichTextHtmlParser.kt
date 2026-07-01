// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.richtext

import kotlin.math.roundToInt

/**
 * Parses the narrow HTML subset [RichTextHtml.toHtml] emits (plus tolerated variants: `strong`/`em`,
 * `del`/`strike`, `px` font sizes, `#rgb` colors, `start`/`end` alignment, and pretty-printer
 * whitespace) back into a [RichTextContent]. Unknown tags and unknown CSS properties are ignored
 * without dropping the text they wrap. A small state machine keeps the nesting shallow:
 * [handleTag] dispatches to one-liner helpers and [handleText] appends decoded text; open
 * style-bearing tags live on a stack so spans of any kind can nest and interleave.
 */
internal class RichTextHtmlParser(private val html: String) {
    private val text = StringBuilder()
    private val spans = ArrayList<RichSpan>()
    private val links = ArrayList<RichLink>()
    private val alignments = ArrayList<RichAlignment>()
    private val images = ArrayList<RichImage>()
    private val openStyles = ArrayDeque<OpenStyles>()
    private var baseStyle: RichBaseStyle? = null
    private var linkStart = -1
    private var linkUrl = ""
    private var listType: Char? = null
    private var olCount = 0
    private var inQuote = false
    private var blockStart = -1
    private var blockAlign: RichAlign? = null

    /** One still-open style-bearing tag: the [channel] whose closing tag ends it, and its styles. */
    private class OpenStyles(val channel: String, val start: Int, val styles: List<RichStyle>)

    fun parse(): RichTextContent {
        var i = 0
        while (i < html.length) {
            if (html[i] == '<') {
                val gt = html.indexOf('>', i)
                if (gt == -1) break
                handleTag(html.substring(i + 1, gt).trim())
                i = gt + 1
            } else {
                val lt = html.indexOf('<', i)
                val end = if (lt == -1) html.length else lt
                handleText(html.substring(i, end))
                i = end
            }
        }
        return finish()
    }

    private fun atLineStart() = text.isEmpty() || text.last() == '\n'

    private fun newlineIfNeeded() {
        if (!atLineStart()) text.append('\n')
    }

    private fun handleTag(raw: String) {
        val closing = raw.startsWith("/")
        val body = raw.removePrefix("/").trim()
        val name = body.substringBefore(' ').substringBefore('/').lowercase()
        if (!handleInlineTag(name, closing, body)) handleBlockTag(name, closing, body)
    }

    /** Dispatches character-level tags; returns false when [name] is not an inline tag. */
    private fun handleInlineTag(name: String, closing: Boolean, body: String): Boolean {
        when (name) {
            "b", "strong" -> toggleStyles(closing, "b", listOf(RichStyle.Bold))
            "i", "em" -> toggleStyles(closing, "i", listOf(RichStyle.Italic))
            "u" -> toggleStyles(closing, "u", listOf(RichStyle.Underline))
            "s", "del", "strike" -> toggleStyles(closing, "s", listOf(RichStyle.Strikethrough))
            "span" -> toggleStyles(closing, "span", spanStyles(closing, body))
            "a" -> handleAnchor(closing, body)
            "img" -> if (!closing) handleImage(body)
            else -> return false
        }
        return true
    }

    private fun handleBlockTag(name: String, closing: Boolean, body: String) {
        when (name) {
            "br" -> {
                text.append('\n')
                if (inQuote) text.append(QUOTE_PREFIX)
            }
            "ul" -> handleList(closing, 'u')
            "ol" -> handleList(closing, 'o')
            "li" -> handleListItem(closing, body)
            "blockquote" -> handleQuote(closing)
            "p" -> handleParagraph(closing, body)
            "div" -> handleDiv(closing, body)
            else -> Unit
        }
    }

    private fun spanStyles(closing: Boolean, body: String): List<RichStyle> =
        if (closing) emptyList() else parseInlineStyles(extractStyleAttr(body))

    /** Opens [styles] on [channel], or on a closing tag records spans for the matching open tag. */
    private fun toggleStyles(closing: Boolean, channel: String, styles: List<RichStyle>) {
        if (!closing) {
            openStyles.addLast(OpenStyles(channel, text.length, styles))
            return
        }
        val idx = openStyles.indexOfLast { it.channel == channel }
        if (idx >= 0) recordSpans(openStyles.removeAt(idx), text.length)
    }

    private fun recordSpans(open: OpenStyles, end: Int) {
        if (open.start >= end) return
        open.styles.forEach { style -> spans.add(RichSpan(open.start, end, style)) }
    }

    private fun handleAnchor(closing: Boolean, body: String) {
        if (closing) {
            if (linkStart >= 0) {
                links.add(RichLink(linkStart, text.length, linkUrl))
                linkStart = -1
                linkUrl = ""
            }
        } else {
            linkStart = text.length
            linkUrl = extractHref(body)
        }
    }

    /** `<img src="cid:…" alt="name">` becomes a visible [imageToken] backed by a [RichImage]. */
    private fun handleImage(body: String) {
        val src = extractAttr(body, SRC_ATTR) ?: return
        if (!src.startsWith(CID_PREFIX)) return
        val name = extractAttr(body, ALT_ATTR).orEmpty()
        val start = text.length
        text.append(imageToken(name))
        images.add(RichImage(start, text.length, src.removePrefix(CID_PREFIX), name))
    }

    private fun handleList(closing: Boolean, type: Char) {
        if (closing) {
            listType = null
        } else {
            listType = type
            if (type == 'o') olCount = 0
        }
        newlineIfNeeded()
    }

    private fun handleListItem(closing: Boolean, body: String) {
        if (closing) {
            endAlignedBlock()
            return
        }
        newlineIfNeeded()
        // The alignment run starts at the raw line start, so it covers the block marker too.
        startAlignedBlock(parseTextAlign(extractStyleAttr(body)))
        if (listType == 'o') {
            olCount++
            text.append("$olCount. ")
        } else {
            text.append(BULLET_PREFIX)
        }
    }

    private fun handleQuote(closing: Boolean) {
        newlineIfNeeded()
        inQuote = !closing
        if (!closing) text.append(QUOTE_PREFIX)
    }

    private fun handleParagraph(closing: Boolean, body: String) {
        if (closing) {
            endAlignedBlock()
            newlineIfNeeded()
        } else {
            newlineIfNeeded()
            startAlignedBlock(parseTextAlign(extractStyleAttr(body)))
        }
    }

    /**
     * A leading `<div style>` before any content is the base-style wrapper [RichTextHtml.toHtml]
     * emits — it must not add a newline (and its closing tag lands right after the last block's
     * newline, where [newlineIfNeeded] is a no-op, so the wrapper never adds stray text).
     */
    private fun handleDiv(closing: Boolean, body: String) {
        val wrapperCss = if (!closing && text.isEmpty() && baseStyle == null) extractStyleAttr(body) else null
        if (wrapperCss != null) {
            baseStyle = parseBaseStyle(wrapperCss)
        } else {
            newlineIfNeeded()
        }
    }

    private fun startAlignedBlock(align: RichAlign?) {
        endAlignedBlock()
        blockStart = text.length
        blockAlign = align
    }

    private fun endAlignedBlock() {
        val align = blockAlign
        if (align != null && blockStart >= 0 && blockStart < text.length) {
            alignments.add(RichAlignment(blockStart, text.length, align))
        }
        blockStart = -1
        blockAlign = null
    }

    private fun handleText(chunk: String) {
        // Drop the insignificant whitespace a pretty-printer leaves between block tags (blank runs at
        // a line start, or any blank run with a newline), but keep a real space between inline runs.
        if (!(chunk.isBlank() && (atLineStart() || chunk.contains('\n')))) text.append(unescape(chunk))
    }

    private fun finish(): RichTextContent {
        endAlignedBlock()
        while (openStyles.isNotEmpty()) recordSpans(openStyles.removeLast(), text.length)
        if (linkStart >= 0) links.add(RichLink(linkStart, text.length, linkUrl))
        val out = text.toString().trimEnd('\n')
        val len = out.length
        return RichTextContent(
            text = out,
            spans = mergedSpans(spans.mapNotNull { it.clampedTo(len) }),
            links = links.mapNotNull { it.clampedTo(len) },
            alignments = mergedAlignments(alignments.mapNotNull { it.clampedTo(len) }),
            images = images.filter { it.end <= len },
            baseStyle = baseStyle,
        )
    }
}

private fun RichSpan.clampedTo(len: Int): RichSpan? {
    val e = minOf(end, len)
    return if (start < e) copy(end = e) else null
}

private fun RichLink.clampedTo(len: Int): RichLink? {
    val e = minOf(end, len)
    return if (start < e) copy(end = e) else null
}

private fun RichAlignment.clampedTo(len: Int): RichAlignment? {
    val e = minOf(end, len)
    return if (start < e) copy(end = e) else null
}

/** Merges same-style spans that touch or overlap, yielding the canonical maximal-run form. */
private fun mergedSpans(spans: List<RichSpan>): List<RichSpan> {
    val merged = ArrayList<RichSpan>()
    for (span in spans.sortedWith(compareBy({ it.start }, { it.end }))) {
        val i = merged.indexOfLast { it.style == span.style && span.start <= it.end }
        if (i >= 0) {
            merged[i] = merged[i].copy(end = maxOf(merged[i].end, span.end))
        } else {
            merged.add(span)
        }
    }
    return merged
}

/** Merges same-alignment runs on adjacent lines (ranges separated by exactly the newline). */
private fun mergedAlignments(alignments: List<RichAlignment>): List<RichAlignment> {
    val merged = ArrayList<RichAlignment>()
    for (alignment in alignments.sortedBy { it.start }) {
        val last = merged.lastOrNull()
        if (last != null && last.align == alignment.align && alignment.start <= last.end + 1) {
            merged[merged.size - 1] = last.copy(end = maxOf(last.end, alignment.end))
        } else {
            merged.add(alignment)
        }
    }
    return merged
}

// --- tag-attribute and CSS helpers ---

private const val CID_PREFIX = "cid:"

private fun attrRegex(name: String) =
    Regex("(?:^|\\s)$name\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE)

private val HREF_ATTR = attrRegex("href")
private val STYLE_ATTR = attrRegex("style")
private val SRC_ATTR = attrRegex("src")
private val ALT_ATTR = attrRegex("alt")

private fun extractAttr(tagBody: String, attr: Regex): String? {
    val match = attr.find(tagBody) ?: return null
    val (doubleQuoted, singleQuoted) = match.destructured
    return unescape(doubleQuoted.ifEmpty { singleQuoted })
}

internal fun extractHref(tagBody: String): String = extractAttr(tagBody, HREF_ATTR) ?: ""

/** The tag's `style` attribute value, or null when absent (an empty attribute yields ""). */
internal fun extractStyleAttr(tagBody: String): String? = extractAttr(tagBody, STYLE_ATTR)

private inline fun forEachCssDeclaration(css: String, action: (prop: String, value: String) -> Unit) {
    css.split(';').forEach { declaration ->
        val prop = declaration.substringBefore(':').trim().lowercase()
        val value = declaration.substringAfter(':', "").trim()
        if (prop.isNotEmpty() && value.isNotEmpty()) action(prop, value)
    }
}

/** Maps recognized inline CSS declarations to [RichStyle]s; unknown properties are skipped. */
internal fun parseInlineStyles(css: String?): List<RichStyle> {
    if (css == null) return emptyList()
    val styles = ArrayList<RichStyle>()
    forEachCssDeclaration(css) { prop, value -> styleForCss(prop, value)?.let(styles::add) }
    return styles
}

private fun styleForCss(prop: String, value: String): RichStyle? = when (prop) {
    "font-family" -> RichStyle.FontFamily(value)
    "font-size" -> parseFontSizePt(value)?.let { RichStyle.FontSize(it) }
    "color" -> parseCssColor(value)?.let { RichStyle.FontColor(it) }
    "background-color" -> parseCssColor(value)?.let { RichStyle.Highlight(it) }
    else -> null
}

/** Reads the base-style wrapper's declarations; unknown properties are ignored. */
internal fun parseBaseStyle(css: String): RichBaseStyle {
    var fontCss: String? = null
    var fontSizePt: Int? = null
    forEachCssDeclaration(css) { prop, value ->
        when (prop) {
            "font-family" -> fontCss = value
            "font-size" -> fontSizePt = parseFontSizePt(value)
        }
    }
    return RichBaseStyle(fontCss, fontSizePt)
}

private val FONT_SIZE = Regex("^(\\d+(?:\\.\\d+)?)\\s*(pt|px)$", RegexOption.IGNORE_CASE)
private const val PT_PER_PX = 3.0 / 4.0

/** A CSS font size in points; `px` values convert at 3/4 pt per px, rounded to the nearest int. */
internal fun parseFontSizePt(value: String): Int? {
    val match = FONT_SIZE.find(value) ?: return null
    val (number, unit) = match.destructured
    val size = number.toDoubleOrNull() ?: return null
    val pt = if (unit.equals("px", ignoreCase = true)) size * PT_PER_PX else size
    return pt.roundToInt().takeIf { it > 0 }
}

private const val OPAQUE_ALPHA = 0xFF000000.toInt()
private const val SHORT_HEX_LEN = 3
private const val LONG_HEX_LEN = 6
private const val COLOR_RADIX = 16

/** `#rgb` or `#rrggbb` as an opaque ARGB int, or null for anything else. */
internal fun parseCssColor(value: String): Int? {
    if (!value.startsWith("#")) return null
    val hex = value.drop(1)
    val expanded = when (hex.length) {
        SHORT_HEX_LEN -> buildString { hex.forEach { append(it).append(it) } }
        LONG_HEX_LEN -> hex
        else -> return null
    }
    val rgb = expanded.toIntOrNull(COLOR_RADIX) ?: return null
    return OPAQUE_ALPHA or rgb
}

/** The [RichAlign] from a style attribute's `text-align` declaration, or null. */
internal fun parseTextAlign(css: String?): RichAlign? {
    if (css == null) return null
    var align: RichAlign? = null
    forEachCssDeclaration(css) { prop, value ->
        if (prop == "text-align") align = richAlignFor(value.lowercase())
    }
    return align
}

private fun richAlignFor(value: String): RichAlign? = when (value) {
    "left", "start" -> RichAlign.START
    "center" -> RichAlign.CENTER
    "right", "end" -> RichAlign.END
    else -> null
}

internal fun unescape(s: String): String = s
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&apos;", "'")
    .replace("&nbsp;", " ")
    .replace("&amp;", "&")
