// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.compose

import java.io.ByteArrayOutputStream

/**
 * The fields used to pre-fill the compose screen when it is opened from a `mailto:` link or a
 * "send email" share intent. [isEmpty] is true when nothing worth composing was supplied.
 */
data class ComposePrefill(
    val to: String = "",
    val cc: String = "",
    val bcc: String = "",
    val subject: String = "",
    val body: String = "",
) {
    val isEmpty: Boolean
        get() = to.isBlank() && cc.isBlank() && bcc.isBlank() && subject.isBlank() && body.isBlank()
}

/**
 * Parses a `mailto:` URI (RFC 6068) into a [ComposePrefill].
 *
 * Pure Kotlin — it deliberately avoids `android.net.Uri` (a no-op stub in JVM unit tests, and its
 * opaque-URI handling drops `mailto` query parameters anyway) so it is fully unit-testable. It
 * supports multiple recipients, the `to`/`cc`/`bcc`/`subject`/`body` header fields (case-insensitive
 * and mergeable with any address list before the `?`), and RFC 3986 percent-encoding.
 *
 * A literal `+` is preserved rather than decoded to a space: unlike `application/x-www-form-urlencoded`,
 * `mailto` encodes spaces as `%20`, so `+` is a real character and addresses like `user+tag@example.com`
 * survive intact.
 */
object MailtoParser {

    private const val SCHEME = "mailto:"
    private const val ESCAPE_LENGTH = 3 // a '%' plus two hex digits
    private const val HEX_RADIX = 16

    fun parse(uri: String): ComposePrefill {
        val afterScheme = stripScheme(uri.trim())
        val queryStart = afterScheme.indexOf('?')
        val toPart = if (queryStart >= 0) afterScheme.substring(0, queryStart) else afterScheme
        val query = if (queryStart >= 0) afterScheme.substring(queryStart + 1) else ""

        val params = parseQuery(query)
        val to = addresses(toPart) + params["to"].orEmpty().flatMap(::addresses)
        return ComposePrefill(
            to = to.joinToString(", "),
            cc = params["cc"].orEmpty().flatMap(::addresses).joinToString(", "),
            bcc = params["bcc"].orEmpty().flatMap(::addresses).joinToString(", "),
            // First occurrence wins for these single-valued fields (RFC 6068 leaves duplicates undefined).
            subject = params["subject"]?.firstOrNull()?.let(::percentDecode).orEmpty(),
            body = params["body"]?.firstOrNull()?.let(::percentDecode).orEmpty(),
        )
    }

    private fun stripScheme(value: String): String =
        if (value.regionMatches(0, SCHEME, 0, SCHEME.length, ignoreCase = true)) {
            value.substring(SCHEME.length)
        } else {
            value
        }

    /** Splits a `k=v&k=v` query into a map of lowercased field name to its raw (still-encoded) values. */
    private fun parseQuery(query: String): Map<String, List<String>> {
        if (query.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, MutableList<String>>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val name = if (eq >= 0) pair.substring(0, eq) else pair
            val rawValue = if (eq >= 0) pair.substring(eq + 1) else ""
            result.getOrPut(name.lowercase()) { mutableListOf() }.add(rawValue)
        }
        return result
    }

    /** Splits a comma-separated, percent-encoded address list into decoded, non-blank addresses. */
    private fun addresses(part: String): List<String> = part.split(',')
        .map { percentDecode(it).trim() }
        .filter { it.isNotEmpty() }

    /**
     * Decodes RFC 3986 `%XX` escapes as UTF-8 bytes. Leaves `+` untouched (see class doc) and passes
     * any malformed escape (`%` not followed by two hex digits) through verbatim.
     */
    private fun percentDecode(value: String): String {
        if ('%' !in value) return value
        val out = ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            val decoded = if (c == '%') decodeEscape(value, i) else null
            if (decoded != null) {
                out.write(decoded)
                i += ESCAPE_LENGTH
            } else {
                val bytes = c.toString().toByteArray(Charsets.UTF_8)
                out.write(bytes, 0, bytes.size)
                i++
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** The byte value of the two hex digits following the `%` at [start], or null if malformed. */
    private fun decodeEscape(value: String, start: Int): Int? {
        if (start + ESCAPE_LENGTH > value.length) return null
        return value.substring(start + 1, start + ESCAPE_LENGTH).toIntOrNull(HEX_RADIX)
    }
}
