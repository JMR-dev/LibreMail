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
    //
    // The (folder, inInbox, timestampMillis) index serves the unified-inbox summary scan (issue #187):
    // MessageDao.pagingUnifiedFolderSummaries filters `folder = ? AND inInbox = 1 ORDER BY
    // timestampMillis DESC` with no folder-leading index, so it SCANned the whole table via
    // index_messages_timestampMillis and filtered per row. This index makes the two equalities an
    // index seek and supplies the timestampMillis ordering, turning the SCAN into a bounded SEARCH
    // with no temp B-tree sort (verified via EXPLAIN QUERY PLAN).
    indices = [
        Index("accountId"),
        Index("timestampMillis"),
        Index("accountId", "folder", "uid"),
        Index("folder", "inInbox", "timestampMillis"),
    ],
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
    // Unicode-casefolded copies of the searchable fields (issue #232). SQLite's LIKE folds case only for
    // ASCII, so search matches against these `lowercase()` copies (Kotlin's lowercase is Unicode-aware)
    // for case-insensitive Unicode search. Kept in sync wherever their source is written — toEntity,
    // MessageDao.updateBody (snippet), and MessageDao.updateHeaderContent (headers).
    @ColumnInfo(defaultValue = "") val senderFold: String = "",
    @ColumnInfo(defaultValue = "") val senderEmailFold: String = "",
    @ColumnInfo(defaultValue = "") val subjectFold: String = "",
    @ColumnInfo(defaultValue = "") val snippetFold: String = "",
)
