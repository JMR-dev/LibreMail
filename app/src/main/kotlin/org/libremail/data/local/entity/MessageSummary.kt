// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local.entity

/**
 * Lightweight projection of [MessageEntity] for the mailbox list: every column the list renders
 * or searches, but *not* the potentially large `body`/`isHtml`.
 *
 * The list observes every cached message at once, so selecting full HTML bodies would drag them
 * all through SQLite's shared (~2 MB) CursorWindow and overflow it once enough large bodies are
 * cached — crashing with "Couldn't read row N from CursorWindow" (issue #51). Bodies are read
 * lazily, one message at a time, via `MessageDao.getById` when a message is opened.
 */
data class MessageSummary(
    val id: String,
    val accountId: String,
    val sender: String,
    val senderEmail: String,
    val subject: String,
    val snippet: String,
    val timestampMillis: Long,
    val isRead: Boolean,
    val isStarred: Boolean,
    val folder: String,
    val inInbox: Boolean,
    val bodyFetched: Boolean,
)
