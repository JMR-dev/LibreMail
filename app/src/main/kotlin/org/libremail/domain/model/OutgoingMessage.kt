// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.domain.model

/**
 * A message the user is sending. [to]/[cc] are comma-separated address lists.
 *
 * [body] is always the plain-text form. [bodyHtml] carries the HTML form when the message was
 * composed with formatting; when it is null the message is sent as `text/plain` only (unchanged
 * from the plaintext-only path), otherwise as `multipart/alternative` with both parts.
 */
data class OutgoingMessage(
    val accountId: String,
    val to: String,
    val cc: String = "",
    val subject: String,
    val body: String,
    val bodyHtml: String? = null,
    val attachments: List<OutgoingAttachment> = emptyList(),
)

/** A file the user attached, referenced by its content-URI string until it is sent. */
data class OutgoingAttachment(val uri: String, val name: String)
