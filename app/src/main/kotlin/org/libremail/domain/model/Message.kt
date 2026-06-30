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
    /** The folder this message belongs to, e.g. "INBOX" or "[Gmail]/Sent Mail". */
    val folder: String = "INBOX",
    /** True for messages synced from a folder; false for transient server-search hits. */
    val inInbox: Boolean = true,
    /** True once the body has been cached locally, i.e. the message can be opened offline. */
    val bodyFetched: Boolean = false,
)
