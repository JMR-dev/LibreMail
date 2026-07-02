// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.domain.model

data class Attachment(
    val messageId: String,
    val partIndex: Int,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    /**
     * The normalized `Content-ID` when this part is an inline image referenced from the HTML body via
     * `cid:` — null for an ordinary attachment. Inline parts are cached (so the reader can resolve
     * `cid:` requests) but filtered out of the user-facing attachment list.
     */
    val contentId: String? = null,
)
