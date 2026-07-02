// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local.entity

/**
 * Aggregate projection of [MessageEntity] counting unread, folder-synced rows per (account, folder).
 * Produced by `MessageDao.observeUnreadCounts`' `GROUP BY` query — never a stored table — so the
 * counts come straight from SQLite without pulling any message rows into memory.
 */
data class FolderUnreadCount(val accountId: String, val folder: String, val unreadCount: Int)
