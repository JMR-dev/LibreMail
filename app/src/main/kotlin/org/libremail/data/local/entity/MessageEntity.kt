// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    // The (accountId, folder, uid) index serves the folder-scoped UID probes the backfill/reconcile
    // hot paths run on every page/sync: MIN(uid) (lowestSyncedUid) and the uid >= window bound
    // (deleteSyncedInWindowNotIn / syncedIdsBeyondCountInFolder).
    indices = [Index("accountId"), Index("timestampMillis"), Index("accountId", "folder", "uid")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val sender: String,
    val senderEmail: String,
    val subject: String,
    val snippet: String,
    val body: String,
    val isHtml: Boolean = false,
    val timestampMillis: Long,
    val isRead: Boolean,
    val isStarred: Boolean,
    /** The folder this message belongs to, e.g. "INBOX" or "[Gmail]/Sent Mail". */
    @ColumnInfo(defaultValue = "INBOX") val folder: String = "INBOX",
    /** True for folder-synced rows; false for transient server-search hits (purged on search close). */
    val inInbox: Boolean = true,
    /** True once the body has been fetched from the server (distinguishes "not fetched" from "empty body"). */
    val bodyFetched: Boolean = false,
    /**
     * The server IMAP UID as a number (also embedded in [id]). Materialized as a column so full-history
     * backfill can page by "lowest cached UID" and foreground sync can reconcile only the recent UID
     * window without deleting older, backfilled history. 0 for rows migrated before this column existed
     * (refreshed to the real UID on the next sync).
     */
    @ColumnInfo(defaultValue = "0") val uid: Long = 0L,
)
