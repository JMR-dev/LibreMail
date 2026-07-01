// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data

import org.libremail.domain.model.Signature
import org.libremail.richtext.RichTextContent
import org.libremail.richtext.RichTextHtml

/**
 * A signature rendered into both forms the composer needs: [plain] for the plaintext body/fallback
 * and [html] for the HTML body. The two are kept in sync — parsing [html] back through the rich-text
 * model yields exactly [plain] — so the editor shows the same content whichever it seeds from, and a
 * From-account swap can strip the previously applied block from either representation.
 *
 * The block opens with the RFC 3676 "-- " delimiter so receiving clients recognize it as a signature.
 */
data class SignatureBlock(val plain: String, val html: String) {
    val isEmpty: Boolean get() = plain.isEmpty() && html.isEmpty()

    companion object {
        val EMPTY = SignatureBlock("", "")

        /** The block for [signature], or [EMPTY] when there is none / it is blank. */
        fun of(signature: Signature?): SignatureBlock {
            if (signature == null) return EMPTY
            val plainSig = signature.plainText().trimEnd()
            if (plainSig.isBlank() && signature.html.isBlank()) return EMPTY
            return SignatureBlock(
                plain = "$DELIMITER_PLAIN$plainSig",
                html = RichTextHtml.toHtml(RichTextContent(DELIMITER_PLAIN)) + signature.html,
            )
        }

        private const val DELIMITER_PLAIN = "\n\n-- \n"
    }
}
