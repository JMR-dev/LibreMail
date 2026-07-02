// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data

import org.libremail.mail.HtmlToText

/**
 * Derives the one-line mailbox preview snippet that is persisted alongside a fetched body.
 *
 * HTML bodies are reduced to readable text via [HtmlToText] (script/style *content* dropped, tags
 * stripped, entities decoded) before the whitespace collapsing. Plain-text bodies get no markup
 * handling at all — literal `<`/`>` characters survive — only whitespace collapsing. Both paths end
 * with the same [MAX_LENGTH] cap.
 *
 * Derivation runs once, when a body is fetched and cached (plus the one-off migration backfill) —
 * never per mailbox-list row.
 */
object Snippet {

    const val MAX_LENGTH = 140

    private val WHITESPACE = Regex("\\s+")

    fun of(body: String, isHtml: Boolean): String {
        val text = if (isHtml) HtmlToText.convert(body) else body
        return text.replace(WHITESPACE, " ").trim().take(MAX_LENGTH)
    }
}
