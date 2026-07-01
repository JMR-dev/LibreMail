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

    private val BLOCK_BREAK = Regex(
        "(?i)</?(p|div|tr|table|ul|ol|h[1-6]|blockquote)\\b[^>]*>|<br\\s*/?>",
    )
    private val LIST_ITEM = Regex("(?i)<li\\b[^>]*>")

    fun convert(html: String): String {
        var s = html
        // Drop script/style contents outright so their text never leaks into the output.
        s = s.replace(Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1>"), "")
        s = LIST_ITEM.replace(s, "\n• ")
        s = BLOCK_BREAK.replace(s, "\n")
        s = s.replace(Regex("<[^>]*>"), "")
        s = decodeEntities(s)
        // Collapse runs of spaces/tabs, then trim trailing spaces and cap consecutive blank lines.
        s = s.replace(Regex("[ \\t]+"), " ")
        s = s.lineSequence().joinToString("\n") { it.trim() }
        s = s.replace(Regex("\n{3,}"), "\n\n")
        return s.trim()
    }

    private fun decodeEntities(s: String): String = s
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&mdash;", "—")
        .replace("&ndash;", "–")
        .replace("&amp;", "&")
}
