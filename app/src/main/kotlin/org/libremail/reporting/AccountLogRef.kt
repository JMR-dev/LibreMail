// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.reporting

import java.security.MessageDigest

/**
 * A short, stable, non-reversible reference for an account — safe to write to logs and a [DebugReport].
 *
 * [Account.id][org.libremail.domain.model.Account.id] embeds the raw email address (e.g.
 * `"outlook:user@domain.com"` or `"imap:user@domain.com"`), so it is PII and must **never** be logged
 * directly. This returns the id's scheme prefix followed by a truncated SHA-256 of the whole id — e.g.
 * `"outlook:a1b2c3"` — which is:
 *
 * - **stable**: the same id always maps to the same reference (a hash, not a random token), so lines
 *   for one account correlate across a session and across reports;
 * - **non-reversible**: a truncated one-way hash cannot be turned back into the email;
 * - **non-PII**: the scheme (`outlook`/`imap`) names the auth kind, not the user, and the hex hash
 *   contains no `@`, domain, or local part. If the part before the first `:` is not a bare token
 *   (e.g. an id that is itself an address), it is replaced with [GENERIC_SCHEME] so no address can
 *   leak through the prefix.
 */
fun accountLogRef(accountId: String): String {
    val candidate = accountId.substringBefore(':', missingDelimiterValue = "")
    val scheme = candidate.takeIf(::isBareScheme) ?: GENERIC_SCHEME
    return "$scheme:${sha256Hex(accountId).take(REF_HASH_LENGTH)}"
}

/** Prefix used when an id has no scheme, or one that could itself carry PII (an `@`, a dot, …). */
private const val GENERIC_SCHEME = "acct"

/** Hex characters of the SHA-256 kept in the reference; short but collision-safe for a device's few accounts. */
private const val REF_HASH_LENGTH = 6

/** A bare scheme token is letters/digits only, so an address (with `@`/`.`) can never pass as a scheme. */
private fun isBareScheme(text: String): Boolean = text.isNotEmpty() && text.all(Char::isLetterOrDigit)

private fun sha256Hex(input: String): String = MessageDigest.getInstance("SHA-256")
    .digest(input.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
