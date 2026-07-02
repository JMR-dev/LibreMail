// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.domain.model

/**
 * The number of unread, folder-synced messages in one account's folder. Emitted per (account, folder)
 * pair that currently holds unread mail; pairs with no unread mail are simply absent. Feeds the
 * drawer's per-folder unread badges (#83) and the bold styling of accounts that have unread mail (#84).
 */
data class UnreadCount(val accountId: String, val folder: String, val count: Int)
