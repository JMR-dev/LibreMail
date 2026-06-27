// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.domain.model

data class Message(
    val id: String,
    val accountId: String,
    val sender: String,
    val senderEmail: String,
    val subject: String,
    val snippet: String,
    val body: String,
    val isHtml: Boolean,
    val timestampMillis: Long,
    val isRead: Boolean,
    val isStarred: Boolean,
)
