// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.compose

import android.content.Intent

/**
 * Turns an inbound Android intent into a [ComposePrefill], so tapping a `mailto:` link or sharing to
 * LibreMail as an email opens a pre-filled compose screen.
 *
 * Handles:
 *  - `ACTION_VIEW` / `ACTION_SENDTO` with a `mailto:` URI (delegates to [MailtoParser]).
 *  - `ACTION_SEND` / `ACTION_SEND_MULTIPLE` email shares, reading the standard `EXTRA_EMAIL`,
 *    `EXTRA_CC`, `EXTRA_BCC`, `EXTRA_SUBJECT` and `EXTRA_TEXT` extras.
 *
 * Extras fill in only the fields the URI left blank, so a `mailto:` URI always takes precedence.
 * Returns `null` for anything that isn't a mail intent (e.g. the plain launcher intent), or when the
 * intent carried nothing to compose.
 */
object IntentComposeParser {

    fun parse(intent: Intent?): ComposePrefill? {
        if (intent == null) return null
        val prefill = when (intent.action) {
            Intent.ACTION_VIEW, Intent.ACTION_SENDTO -> fromMailto(intent)
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> fromShare(intent)
            else -> null
        }
        return prefill?.takeUnless { it.isEmpty }
    }

    private fun fromMailto(intent: Intent): ComposePrefill? {
        val data = intent.dataString
        if (data == null || !data.startsWith(MAILTO, ignoreCase = true)) return null
        return MailtoParser.parse(data).fillBlanksFrom(intent)
    }

    private fun fromShare(intent: Intent): ComposePrefill {
        // A share may (rarely) also carry a mailto: URI; honour it, then fill from the extras.
        val base = intent.dataString
            ?.takeIf { it.startsWith(MAILTO, ignoreCase = true) }
            ?.let { MailtoParser.parse(it) }
            ?: ComposePrefill()
        return base.fillBlanksFrom(intent)
    }

    /** Fills each empty field from the corresponding email intent extra, leaving set fields intact. */
    private fun ComposePrefill.fillBlanksFrom(intent: Intent): ComposePrefill = ComposePrefill(
        to = to.ifBlank { addressExtra(intent, Intent.EXTRA_EMAIL) },
        cc = cc.ifBlank { addressExtra(intent, Intent.EXTRA_CC) },
        bcc = bcc.ifBlank { addressExtra(intent, Intent.EXTRA_BCC) },
        subject = subject.ifBlank { intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty() },
        body = body.ifBlank { intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty() },
    )

    /** Reads an address extra that may be a `String[]` (the documented form) or a single `String`. */
    private fun addressExtra(intent: Intent, key: String): String {
        intent.getStringArrayExtra(key)?.let { array ->
            return array.filter { it.isNotBlank() }.joinToString(", ")
        }
        return intent.getStringExtra(key).orEmpty()
    }

    private const val MAILTO = "mailto:"
}
