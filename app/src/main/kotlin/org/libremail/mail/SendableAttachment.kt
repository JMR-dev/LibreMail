// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail

import java.io.File

/**
 * One staged attachment ready to send: the on-disk [file] plus how it should be attached. An inline
 * image has [isInline] true and a non-null [contentId] — the body's HTML references it as
 * `cid:contentId`, so the SMTP sender emits it inside a `multipart/related` with a matching
 * `Content-ID` + inline disposition, and the Graph sender marks the payload `isInline` with that
 * `contentId`. A regular attachment leaves both defaults and is attached exactly as before.
 */
data class SendableAttachment(val file: File, val contentId: String? = null, val isInline: Boolean = false) {
    /** True only for a genuine inline image (inline flag set and a content id to reference it by). */
    val isInlineImage: Boolean get() = isInline && contentId != null
}
