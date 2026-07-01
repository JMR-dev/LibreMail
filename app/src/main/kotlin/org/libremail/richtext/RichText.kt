// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.richtext

/**
 * Inline character styles the compose editor supports. The simple toggles are singletons; the
 * parameterized styles carry their value, and a well-formed [RichTextContent] never overlaps two
 * values of the same kind (editing ops replace the old value instead of stacking a second one).
 */
sealed interface RichStyle {
    data object Bold : RichStyle

    data object Italic : RichStyle

    data object Underline : RichStyle

    /** Struck-through text: serialized as `<s>`, also parsed from `<del>`/`<strike>`. */
    data object Strikethrough : RichStyle

    /** A CSS font-family stack (e.g. `"Liberation Serif", serif`), serialized verbatim. */
    data class FontFamily(val css: String) : RichStyle

    /** Font size in points; parsed from `pt` or `px` (px convert at 3/4 pt per px, rounded). */
    data class FontSize(val pt: Int) : RichStyle

    /** Text color as ARGB; serialized as `#rrggbb`, so only opaque colors round-trip. */
    data class FontColor(val argb: Int) : RichStyle

    /** Background highlight as ARGB; serialized as `#rrggbb`, so only opaque colors round-trip. */
    data class Highlight(val argb: Int) : RichStyle
}

/** A run of [style] over the half-open range [[start], [end]) of the plain text. */
data class RichSpan(val start: Int, val end: Int, val style: RichStyle)

/** A hyperlink over the half-open range [[start], [end]) pointing at [url]. */
data class RichLink(val start: Int, val end: Int, val url: String)

/** Paragraph alignment (START is the writing-direction default). */
enum class RichAlign { START, CENTER, END }

/**
 * Paragraph alignment over the half-open range [[start], [end]) of the plain text. Ranges cover
 * whole lines (including any block marker), and adjacent same-aligned lines canonically share one
 * range — [RichTextHtml.fromHtml] always returns that merged form.
 */
data class RichAlignment(val start: Int, val end: Int, val align: RichAlign)

/**
 * An inline image attached by Content-ID. [[start], [end]) covers a visible [imageToken] in the
 * plain text (`[image: name]`), which keeps the text/plain rendering readable; the HTML form
 * replaces the token with `<img src="cid:contentId" alt="name">`.
 */
data class RichImage(val start: Int, val end: Int, val contentId: String, val name: String)

/** A message-wide default font family and/or size, serialized as one outer `<div style>` wrapper. */
data class RichBaseStyle(val fontCss: String? = null, val fontSizePt: Int? = null)

/** The visible plain-text placeholder for an inline image named [name]. */
fun imageToken(name: String): String = "[image: $name]"

/**
 * The compose editor's internal rich-text model: plain [text] plus inline [spans], [links],
 * paragraph [alignments], inline [images], and an optional message-wide [baseStyle].
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
    val alignments: List<RichAlignment> = emptyList(),
    val images: List<RichImage> = emptyList(),
    val baseStyle: RichBaseStyle? = null,
) {
    val isBlank: Boolean get() = text.isBlank()

    /**
     * True when the content carries anything a plaintext field could not represent: inline styling,
     * a link, a block marker, paragraph alignment, an inline image, or a base style. When false,
     * callers should send/persist plaintext only so an unformatted message stays byte-for-byte
     * identical to the old plaintext-only path.
     */
    fun hasFormatting(): Boolean = spans.isNotEmpty() ||
        links.isNotEmpty() ||
        alignments.isNotEmpty() ||
        images.isNotEmpty() ||
        baseStyle != null ||
        text.lineSequence().any { lineMarker(it) != null }
}

/** Recognized block markers and the tags they map to. */
internal const val BULLET_PREFIX = "• "
internal const val QUOTE_PREFIX = "> "
private val ORDERED_PREFIX = Regex("^\\d+\\. ")

/** The block marker prefixing [line], or null for an ordinary paragraph line. */
internal fun lineMarker(line: String): String? = when {
    line.startsWith(BULLET_PREFIX) -> BULLET_PREFIX
    line.startsWith(QUOTE_PREFIX) -> QUOTE_PREFIX
    else -> ORDERED_PREFIX.find(line)?.value
}
