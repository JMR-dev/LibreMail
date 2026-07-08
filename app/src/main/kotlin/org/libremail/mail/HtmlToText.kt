// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail

/**
 * Converts an HTML body into readable plain text for the `text/plain` alternative part and for
 * quoting HTML originals in replies/forwards. Pure (no Android/Compose), so it is unit-testable on
 * the JVM.
 *
 * It is intentionally lightweight — block elements become line breaks, list items gain a "• "
 * marker, tags are dropped and entities decoded — rather than a full HTML renderer. The goal is a
 * legible fallback, not a faithful reproduction.
 */
object HtmlToText {

    // Hoisted out of convert() so each pattern is compiled once, not four times per call — convert()
    // runs once per fetched HTML body during sync, so this is a hot path (#298).
    private val SCRIPT_STYLE = Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1>")
    private val LIST_ITEM = Regex("(?i)<li\\b[^>]*>")
    private val BLOCK_BREAK = Regex(
        "(?i)</?(p|div|tr|table|ul|ol|h[1-6]|blockquote)\\b[^>]*>|<br\\s*/?>",
    )
    private val TAG = Regex("<[^>]*>")
    private val SPACES_AND_TABS = Regex("[ \\t]+")
    private val BLANK_LINES = Regex("\n{3,}")

    fun convert(html: String): String {
        var s = html
        // Drop script/style contents outright so their text never leaks into the output.
        s = SCRIPT_STYLE.replace(s, "")
        s = LIST_ITEM.replace(s, "\n• ")
        s = BLOCK_BREAK.replace(s, "\n")
        s = TAG.replace(s, "")
        s = decodeEntities(s)
        // Collapse runs of spaces/tabs, then trim trailing spaces and cap consecutive blank lines.
        s = SPACES_AND_TABS.replace(s, " ")
        s = s.lineSequence().joinToString("\n") { it.trim() }
        s = BLANK_LINES.replace(s, "\n\n")
        return s.trim()
    }

    private val ENTITY = Regex("&(?:#([0-9]{1,7})|#[xX]([0-9a-fA-F]{1,6})|([a-zA-Z][a-zA-Z0-9]*));")

    private val NAMED_ENTITIES = mapOf(
        "nbsp" to " ",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "mdash" to "—",
        "ndash" to "–",
        "amp" to "&",
    )

    /**
     * Decodes named and numeric (`&#8217;` / `&#x2019;`) character references in a single pass, so a
     * produced character is never re-parsed as the start of another entity (`&amp;lt;` → `&lt;`).
     * Unknown names and out-of-range code points are left as-is.
     */
    private fun decodeEntities(s: String): String = ENTITY.replace(s) { match ->
        val (decimal, hex, name) = match.destructured
        when {
            decimal.isNotEmpty() -> decimal.toIntOrNull()?.toValidChars() ?: match.value
            hex.isNotEmpty() -> hex.toIntOrNull(HEX_RADIX)?.toValidChars() ?: match.value
            else -> NAMED_ENTITIES[name] ?: match.value
        }
    }

    /** The code point as a string, or null when it is not a valid scalar value. */
    private fun Int.toValidChars(): String? = takeIf { it in 1..MAX_CODE_POINT && it !in SURROGATES }
        ?.let { String(Character.toChars(it)) }

    private const val HEX_RADIX = 16
    private const val MAX_CODE_POINT = 0x10FFFF
    private val SURROGATES = 0xD800..0xDFFF
}
