// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.domain.model

/**
 * An inline image embedded in an HTML message body and referenced from it via `cid:<contentId>`
 * (e.g. the mail-piece thumbnails in a USPS Informed Delivery digest). Unlike an [Attachment] it is
 * rendered in place by the reader's WebView — which resolves the `cid:` request to these [bytes] —
 * rather than being offered as a download. Not a `data class`: [bytes] identity/equality is
 * irrelevant and array structural equality would be misleading (matching [DownloadedAttachment]).
 */
class InlineImage(val contentId: String, val mimeType: String, val bytes: ByteArray)
