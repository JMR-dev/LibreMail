// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.richtext

/**
 * Serializes [RichTextContent] to a small, email-safe HTML subset and back. Pure (no Android or
 * Compose types), so the whole conversion is unit-testable on the JVM.
 *
 * The emitted subset — `<b> <i> <u> <s> <a> <span style> <img> <ul>/<ol>/<li> <blockquote> <br>`,
 * `text-align` on `<p>`/`<li>`, and a single outer `<div style>` for [RichBaseStyle] — is
 * deliberately narrow so [fromHtml] is a faithful inverse for anything [toHtml] produces (drafts
 * round-trip losslessly). Two canonical-form caveats: [fromHtml] returns maximally-merged spans and
 * alignment runs, and an inline span crossing a line break re-parses as one span per line.
 */
object RichTextHtml {

    fun toHtml(content: RichTextContent): String {
        val body = bodyHtml(content)
        val base = content.baseStyle ?: return body
        return "<div style=\"" + escapeAttr(baseCss(base)) + "\">" + body + "</div>"
    }

    /** A readable plaintext rendering — the model's [RichTextContent.text] already carries markers. */
    fun toPlainText(content: RichTextContent): String = content.text

    fun fromHtml(html: String): RichTextContent = RichTextHtmlParser(html).parse()
}

/** The `font-family`/`font-size` declarations of the base-style wrapper (possibly empty). */
private fun baseCss(base: RichBaseStyle): String = listOfNotNull(
    base.fontCss?.let { "font-family:$it" },
    base.fontSizePt?.let { "font-size:${it}pt" },
).joinToString(";")

private fun bodyHtml(content: RichTextContent): String {
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

private enum class Kind { PARAGRAPH, BULLET, ORDERED, QUOTE }

/** One line of the plain text: [start] is the raw line start, content excludes the block marker. */
private data class Line(val kind: Kind, val start: Int, val contentStart: Int, val contentEnd: Int)

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
        lines.add(Line(kind, start, start + (marker?.length ?: 0), end))
        if (nl == -1) break
        start = nl + 1
    }
    return lines
}

/** The alignment applying to [line]: the first alignment run overlapping the line's range. */
private fun alignFor(content: RichTextContent, line: Line): RichAlign? =
    content.alignments.firstOrNull { it.start < line.contentEnd && it.end > line.start }?.align

/** Fixed left/center/right keeps the output readable in legacy email clients. */
private fun cssAlign(align: RichAlign): String = when (align) {
    RichAlign.START -> "left"
    RichAlign.CENTER -> "center"
    RichAlign.END -> "right"
}

private fun openBlockTag(tag: String, align: RichAlign?): String =
    if (align == null) "<$tag>" else "<$tag style=\"text-align:${cssAlign(align)}\">"

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
        sb.append(openBlockTag("li", alignFor(content, lines[i])))
        sb.append(renderInline(content, lines[i].contentStart, lines[i].contentEnd))
        sb.append("</li>")
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

/** Merges consecutive plain lines into one `<p>`, splitting where the alignment changes. */
private fun emitParagraph(sb: StringBuilder, content: RichTextContent, lines: List<Line>, from: Int): Int {
    val align = alignFor(content, lines[from])
    sb.append(openBlockTag("p", align))
    var i = from
    while (i < lines.size && lines[i].kind == Kind.PARAGRAPH && alignFor(content, lines[i]) == align) {
        if (i > from) sb.append("<br>")
        sb.append(renderInline(content, lines[i].contentStart, lines[i].contentEnd))
        i++
    }
    sb.append("</p>")
    return i
}

/** Renders the inline styling/links/images over [[from], [to]) as nested, fully-closed tags. */
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

/** The sorted set of offsets where a span, link, or image starts/ends within [[from], [to]]. */
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
    content.images.forEach { add(it.start, it.end) }
    return cuts.toList()
}

/** The simple toggle styles and their HTML tags, in emission (outermost-first) order. */
private val SIMPLE_TAGS = listOf<Pair<RichStyle, String>>(
    RichStyle.Bold to "b",
    RichStyle.Italic to "i",
    RichStyle.Underline to "u",
    RichStyle.Strikethrough to "s",
)

/** Emits one constant-styling run [[a], [b]) with fully-closed tags, so nesting is always valid. */
private fun appendRun(sb: StringBuilder, content: RichTextContent, a: Int, b: Int) {
    val image = content.images.firstOrNull { it.start <= a && a < it.end }
    if (image != null && a != image.start) return // interior cut of an image token; emitted at its start
    val styles = content.spans.filter { it.start <= a && b <= it.end }.map { it.style }
    val link = content.links.firstOrNull { it.start <= a && b <= it.end }
    val tags = buildList {
        if (link != null) add("a href=\"${escapeAttr(link.url)}\"" to "a")
        inlineCss(styles).takeIf { it.isNotEmpty() }?.let { add("span style=\"${escapeAttr(it)}\"" to "span") }
        SIMPLE_TAGS.forEach { (style, tag) -> if (style in styles) add(tag to tag) }
    }
    tags.forEach { (open, _) -> sb.append('<').append(open).append('>') }
    if (image != null) {
        sb.append("<img src=\"cid:").append(escapeAttr(image.contentId))
            .append("\" alt=\"").append(escapeAttr(image.name)).append("\">")
    } else {
        sb.append(escape(content.text.substring(a, b)))
    }
    tags.asReversed().forEach { (_, close) -> sb.append("</").append(close).append('>') }
}

/** Merges the parameterized styles active on a run into one CSS declaration list (maybe empty). */
private fun inlineCss(styles: List<RichStyle>): String {
    val parts = ArrayList<String>()
    styles.firstNotNullOfOrNull { it as? RichStyle.FontFamily }?.let { parts.add("font-family:${it.css}") }
    styles.firstNotNullOfOrNull { it as? RichStyle.FontSize }?.let { parts.add("font-size:${it.pt}pt") }
    styles.firstNotNullOfOrNull { it as? RichStyle.FontColor }?.let { parts.add("color:${cssColor(it.argb)}") }
    styles.firstNotNullOfOrNull { it as? RichStyle.Highlight }
        ?.let { parts.add("background-color:${cssColor(it.argb)}") }
    return parts.joinToString(";")
}

private const val RGB_MASK = 0xFFFFFF
private const val RGB_HEX_DIGITS = 6
private const val HEX_RADIX = 16

/** `#rrggbb` for the low 24 bits of [argb] (alpha is not representable in email-safe CSS). */
internal fun cssColor(argb: Int): String = "#" + (argb and RGB_MASK).toString(HEX_RADIX).padStart(RGB_HEX_DIGITS, '0')

internal fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

internal fun escapeAttr(s: String): String = escape(s).replace("\"", "&quot;")
