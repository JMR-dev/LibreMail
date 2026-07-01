// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.domain.model

/** A message the user is sending. [to]/[cc]/[bcc] are comma-separated address lists. */
data class OutgoingMessage(
    val accountId: String,
    val to: String,
    val cc: String = "",
    val bcc: String = "",
    val subject: String,
    val body: String,
    val attachments: List<OutgoingAttachment> = emptyList(),
)

/** A file the user attached, referenced by its content-URI string until it is sent. */
data class OutgoingAttachment(val uri: String, val name: String)
