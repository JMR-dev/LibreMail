// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local.entity

/**
 * Body-less projection of [MessageEntity] for the repository's routing and flag decisions: every
 * field needed to decide whether to fetch a body, push a SEEN/FLAGGED change, or resolve the
 * message's folder/account — but *not* the potentially large `body` blob.
 *
 * The open path and the flag/move callers (`openMessage`, `downloadAttachment`, `setStarred`,
 * `deleteMessage`, `expunge`, `moveByRole`, `moveToFolder`, `buildReplyDraft`, `prefetchMessage`)
 * never render the body, so pulling it through SQLite's shared CursorWindow on every such read is
 * pure over-fetch (issue #186). `MessageDao.getById` (`SELECT *`) is reserved for the one read that
 * actually returns the body to the reader. Mirrors the existing [MessageSummary] projection — served
 * by the primary-key lookup, so no new index and no schema migration.
 */
data class MessageRouting(
    val id: String,
    val accountId: String,
    val folder: String,
    val uid: Long,
    val isRead: Boolean,
    val isStarred: Boolean,
    val bodyFetched: Boolean,
    val isHtml: Boolean,
)
