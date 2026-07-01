// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.richtext

/** Inline character styles the compose editor supports. */
enum class RichStyle { BOLD, ITALIC, UNDERLINE }

/** A run of [style] over the half-open range [[start], [end]) of the plain text. */
data class RichSpan(val start: Int, val end: Int, val style: RichStyle)

/** A hyperlink over the half-open range [[start], [end]) pointing at [url]. */
data class RichLink(val start: Int, val end: Int, val url: String)

/**
 * The compose editor's internal rich-text model: plain [text] plus inline [spans] and [links].
 *
 * Block structure (unordered/ordered lists and block quotes) is encoded as recognizable line
 * prefixes inside [text] — "• " for bullets, "N. " for numbered items, and "> " for quotes — so
 * the editor can stay a single plain-text field (keeping the plaintext-only experience smooth)
 * while the model still round-trips cleanly to real HTML and to a readable plaintext fallback.
 */
data class RichTextContent(
    val text: String = "",
    val spans: List<RichSpan> = emptyList(),
    val links: List<RichLink> = emptyList(),
) {
    val isBlank: Boolean get() = text.isBlank()

    /**
     * True when the content carries anything a plaintext field could not represent: inline styling,
     * a link, or a block marker. When false, callers should send/persist plaintext only so an
     * unformatted message stays byte-for-byte identical to the old plaintext-only path.
     */
    fun hasFormatting(): Boolean =
        spans.isNotEmpty() || links.isNotEmpty() || text.lineSequence().any { lineMarker(it) != null }
}

/** Recognized block markers and the tags they map to. */
internal const val BULLET_PREFIX = "• "
internal const val QUOTE_PREFIX = "> "
private val ORDERED_PREFIX = Regex("^\\d+\\. ")

private enum class Kind { PARAGRAPH, BULLET, ORDERED, QUOTE }

private data class Line(val kind: Kind, val contentStart: Int, val contentEnd: Int)

/** The block marker prefixing [line], or null for an ordinary paragraph line. */
internal fun lineMarker(line: String): String? = when {
    line.startsWith(BULLET_PREFIX) -> BULLET_PREFIX
    line.startsWith(QUOTE_PREFIX) -> QUOTE_PREFIX
    else -> ORDERED_PREFIX.find(line)?.value
}

/**
 * Serializes [RichTextContent] to a small, email-safe HTML subset and back. Pure (no Android or
 * Compose types), so the whole conversion is unit-testable on the JVM.
 *
 * The emitted subset — `<b> <i> <u> <a> <ul>/<ol>/<li> <blockquote> <br>` — is deliberately narrow
 * so [fromHtml] is a faithful inverse for anything [toHtml] produces (drafts round-trip losslessly).
 */
object RichTextHtml {

    fun toHtml(content: RichTextContent): String {
        if (content.text.isEmpty()) return ""
        val lines = classify(content.text)
        val sb = StringBuilder()
        var i = 0
        while (i < lines.size) {
            i = when (lines[i].kind) {
                Kind.BULLET -> emitList(sb, content, lines, i, Kind.BULLET, "ul")
                Kind.ORDERED -> emitList(sb, content, lines, i, Kind.ORDERED, "ol")
                Kind.QUOTE -> emitQuote(sb, content, lines, i)
                Kind.PARAGRAPH -> emitParagraph(sb, content, lines, i)
            }
        }
        return sb.toString()
    }

    /** A readable plaintext rendering — the model's [RichTextContent.text] already carries markers. */
    fun toPlainText(content: RichTextContent): String = content.text

    fun fromHtml(html: String): RichTextContent = HtmlToRichParser(html).parse()
}

private fun classify(text: String): List<Line> {
    val lines = ArrayList<Line>()
    var start = 0
    while (true) {
        val nl = text.indexOf('\n', start)
        val end = if (nl == -1) text.length else nl
        val marker = lineMarker(text.substring(start, end))
        val kind = when (marker) {
            BULLET_PREFIX -> Kind.BULLET
            QUOTE_PREFIX -> Kind.QUOTE
            null -> Kind.PARAGRAPH
            else -> Kind.ORDERED
        }
        lines.add(Line(kind, start + (marker?.length ?: 0), end))
        if (nl == -1) break
        start = nl + 1
    }
    return lines
}

private fun emitList(
    sb: StringBuilder,
    content: RichTextContent,
    lines: List<Line>,
    from: Int,
    kind: Kind,
    tag: String,
): Int {
    sb.append("<").append(tag).append(">")
    var i = from
    while (i < lines.size && lines[i].kind == kind) {
        sb.append("<li>").append(renderInline(content, lines[i].contentStart, lines[i].contentEnd)).append("</li>")
        i++
    }
    sb.append("</").append(tag).append(">")
    return i
}

private fun emitQuote(sb: StringBuilder, content: RichTextContent, lines: List<Line>, from: Int): Int {
    sb.append("<blockquote>")
    var i = from
    while (i < lines.size && lines[i].kind == Kind.QUOTE) {
        if (i > from) sb.append("<br>")
        sb.append(renderInline(content, lines[i].contentStart, lines[i].contentEnd))
        i++
    }
    sb.append("</blockquote>")
    return i
}

private fun emitParagraph(sb: StringBuilder, content: RichTextContent, lines: List<Line>, from: Int): Int {
    sb.append("<p>")
    var i = from
    while (i < lines.size && lines[i].kind == Kind.PARAGRAPH) {
        if (i > from) sb.append("<br>")
        sb.append(renderInline(content, lines[i].contentStart, lines[i].contentEnd))
        i++
    }
    sb.append("</p>")
    return i
}

/** Renders the inline styling/links over [[from], [to]) as nested `<a>/<b>/<i>/<u>` tags. */
private fun renderInline(content: RichTextContent, from: Int, to: Int): String {
    if (from >= to) return ""
    val points = cutPoints(content, from, to)
    val sb = StringBuilder()
    for (idx in 0 until points.size - 1) {
        val a = points[idx]
        val b = points[idx + 1]
        if (a < b) appendRun(sb, content, a, b)
    }
    return sb.toString()
}

/** The sorted set of offsets where a span or link starts/ends within [[from], [to]]. */
private fun cutPoints(content: RichTextContent, from: Int, to: Int): List<Int> {
    val cuts = sortedSetOf(from, to)
    fun add(start: Int, end: Int) {
        if (end > from && start < to) {
            cuts.add(start.coerceIn(from, to))
            cuts.add(end.coerceIn(from, to))
        }
    }
    content.spans.forEach { add(it.start, it.end) }
    content.links.forEach { add(it.start, it.end) }
    return cuts.toList()
}

/** Emits one constant-styling run [[a], [b]) with fully-closed tags, so nesting is always valid. */
private fun appendRun(sb: StringBuilder, content: RichTextContent, a: Int, b: Int) {
    val styles = content.spans.filter { it.start <= a && b <= it.end }.map { it.style }.toSet()
    val link = content.links.firstOrNull { it.start <= a && b <= it.end }
    if (link != null) sb.append("<a href=\"").append(escapeAttr(link.url)).append("\">")
    if (RichStyle.BOLD in styles) sb.append("<b>")
    if (RichStyle.ITALIC in styles) sb.append("<i>")
    if (RichStyle.UNDERLINE in styles) sb.append("<u>")
    sb.append(escape(content.text.substring(a, b)))
    if (RichStyle.UNDERLINE in styles) sb.append("</u>")
    if (RichStyle.ITALIC in styles) sb.append("</i>")
    if (RichStyle.BOLD in styles) sb.append("</b>")
    if (link != null) sb.append("</a>")
}

/**
 * Parses the narrow HTML subset [toHtml][RichTextHtml.toHtml] emits (plus `strong`/`em` and
 * pretty-printer whitespace) back into a [RichTextContent]. A small state machine keeps the nesting
 * shallow: [handleTag] dispatches to one-liner helpers and [handleText] appends decoded text.
 */
private class HtmlToRichParser(private val html: String) {
    private val text = StringBuilder()
    private val spans = ArrayList<RichSpan>()
    private val links = ArrayList<RichLink>()
    private var boldStart = -1
    private var italicStart = -1
    private var underlineStart = -1
    private var linkStart = -1
    private var linkUrl = ""
    private var listType: Char? = null
    private var olCount = 0
    private var inQuote = false

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
        when (body.substringBefore(' ').substringBefore('/').lowercase()) {
            "br" -> {
                text.append('\n')
                if (inQuote) text.append(QUOTE_PREFIX)
            }
            "b", "strong" -> boldStart = toggle(closing, boldStart, RichStyle.BOLD)
            "i", "em" -> italicStart = toggle(closing, italicStart, RichStyle.ITALIC)
            "u" -> underlineStart = toggle(closing, underlineStart, RichStyle.UNDERLINE)
            "a" -> handleAnchor(closing, body)
            "ul" -> handleList(closing, 'u')
            "ol" -> handleList(closing, 'o')
            "li" -> if (!closing) startListItem()
            "blockquote" -> handleQuote(closing)
            "p", "div" -> newlineIfNeeded()
            else -> Unit
        }
    }

    /** Opens a style (returns the current offset) or closes it (records the span, returns -1). */
    private fun toggle(closing: Boolean, openOffset: Int, style: RichStyle): Int {
        if (!closing) return text.length
        if (openOffset >= 0) spans.add(RichSpan(openOffset, text.length, style))
        return -1
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

    private fun handleList(closing: Boolean, type: Char) {
        if (closing) {
            listType = null
        } else {
            listType = type
            if (type == 'o') olCount = 0
        }
        newlineIfNeeded()
    }

    private fun startListItem() {
        newlineIfNeeded()
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

    private fun handleText(chunk: String) {
        // Drop the insignificant whitespace a pretty-printer leaves between block tags (blank runs at
        // a line start, or any blank run with a newline), but keep a real space between inline runs.
        if (!(chunk.isBlank() && (atLineStart() || chunk.contains('\n')))) text.append(unescape(chunk))
    }

    private fun finish(): RichTextContent {
        val out = text.toString().trimEnd('\n')
        val len = out.length
        if (boldStart in 0 until len) spans.add(RichSpan(boldStart, len, RichStyle.BOLD))
        if (italicStart in 0 until len) spans.add(RichSpan(italicStart, len, RichStyle.ITALIC))
        if (underlineStart in 0 until len) spans.add(RichSpan(underlineStart, len, RichStyle.UNDERLINE))
        if (linkStart in 0 until len) links.add(RichLink(linkStart, len, linkUrl))
        return RichTextContent(
            text = out,
            spans = spans.filter { it.end <= len && it.start < it.end },
            links = links.filter { it.end <= len && it.start < it.end },
        )
    }
}

private fun extractHref(tagBody: String): String {
    val match = Regex("href\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE).find(tagBody) ?: return ""
    val (doubleQuoted, singleQuoted) = match.destructured
    return unescape(doubleQuoted.ifEmpty { singleQuoted })
}

private fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun escapeAttr(s: String): String = escape(s).replace("\"", "&quot;")

private fun unescape(s: String): String = s
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&apos;", "'")
    .replace("&nbsp;", " ")
    .replace("&amp;", "&")
