// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.domain.model

import org.libremail.mail.HtmlToText

/**
 * A named, rich (HTML) email signature belonging to an account. Each account may have several; at
 * most one is its [isDefault], which is the one auto-inserted when composing from that account.
 */
data class Signature(
    val id: String,
    val accountId: String,
    val name: String,
    /** The signature body as HTML (authored in the same rich editor as the message body). */
    val html: String,
    val isDefault: Boolean = false,
) {
    /** The signature as plain text, for the `text/plain` alternative and plaintext-only composing. */
    fun plainText(): String = HtmlToText.convert(html)
}
