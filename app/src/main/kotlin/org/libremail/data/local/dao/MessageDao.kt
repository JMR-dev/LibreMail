// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.libremail.data.local.entity.MessageEntity
import org.libremail.data.local.entity.MessageSummary

@Dao
interface MessageDao {
    /**
     * Mailbox-list projection ordered newest-first. Deliberately omits the large `body`/`isHtml`
     * columns: the list observes every cached message at once, and pulling full bodies through
     * SQLite's shared ~2 MB CursorWindow overflows it once enough large bodies are cached
     * (issue #51). Bodies are loaded lazily per-message via [getById] when a message is opened.
     */
    @Query(
        "SELECT id, accountId, sender, senderEmail, subject, snippet, timestampMillis, " +
            "isRead, isStarred, folder, inInbox, bodyFetched FROM messages ORDER BY timestampMillis DESC",
    )
    fun observeSummaries(): Flow<List<MessageSummary>>

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MessageEntity?

    /** Ids of an account's synced rows in [folder] (excludes transient server-search hits). */
    @Query("SELECT id FROM messages WHERE accountId = :accountId AND folder = :folder AND inInbox = 1")
    suspend fun getSyncedIds(accountId: String, folder: String): List<String>

    /** Ids of synced rows in [folder] whose body hasn't been cached yet (for aggressive prefetch). */
    @Query(
        "SELECT id FROM messages WHERE accountId = :accountId AND folder = :folder " +
            "AND inInbox = 1 AND bodyFetched = 0",
    )
    suspend fun getUnfetchedIds(accountId: String, folder: String): List<String>

    /** Inserts only new messages, leaving existing rows (and their cached bodies/flags) intact. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNew(messages: List<MessageEntity>)

    /**
     * Refreshes the display fields (and the materialized [MessageEntity.uid], keeping it fresh for
     * rows migrated before the column existed) from the server without touching the cached body, the
     * local read/star flags (which may hold an optimistic change the server hasn't reflected yet), or
     * the inbox membership.
     */
    @Query(
        "UPDATE messages SET sender = :sender, senderEmail = :senderEmail, subject = :subject, " +
            "timestampMillis = :timestampMillis, uid = :uid WHERE id = :id",
    )
    suspend fun updateHeaderContent(
        id: String,
        sender: String,
        senderEmail: String,
        subject: String,
        timestampMillis: Long,
        uid: Long,
    )

    /** Marks rows as folder-synced (e.g. a former search-only row that the sync now returns). */
    @Query("UPDATE messages SET inInbox = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("UPDATE messages SET body = :body, isHtml = :isHtml, snippet = :snippet, bodyFetched = 1 WHERE id = :id")
    suspend fun updateBody(id: String, body: String, isHtml: Boolean, snippet: String)

    @Query("UPDATE messages SET isRead = :isRead WHERE id = :id")
    suspend fun setRead(id: String, isRead: Boolean)

    @Query("UPDATE messages SET isStarred = :isStarred WHERE id = :id")
    suspend fun setStarred(id: String, isStarred: Boolean)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Optimistically removes moved/deleted rows; the next sync reconciles if a server op failed. */
    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM messages WHERE accountId = :accountId")
    suspend fun deleteByAccount(accountId: String)

    /** Clears an account's synced rows in [folder] (leaves any in-flight search-only rows). */
    @Query("DELETE FROM messages WHERE accountId = :accountId AND folder = :folder AND inInbox = 1")
    suspend fun deleteSyncedByAccountFolder(accountId: String, folder: String)

    /** Drops synced rows in [folder] for an account that are no longer present on the server. */
    @Query(
        "DELETE FROM messages WHERE accountId = :accountId AND folder = :folder AND inInbox = 1 " +
            "AND id NOT IN (:keepIds)",
    )
    suspend fun deleteSyncedNotIn(accountId: String, folder: String, keepIds: List<String>)

    /**
     * Windowed deletion reconcile for full-history sync (issue #12): within [folder], delete synced
     * rows whose UID falls inside the freshly-fetched recent window (`uid >= minWindowUid`) but which
     * the server no longer returns ([keepIds]). Rows below the window — older history fetched by the
     * background backfill — are deliberately left intact, unlike [deleteSyncedNotIn].
     */
    @Query(
        "DELETE FROM messages WHERE accountId = :accountId AND folder = :folder AND inInbox = 1 " +
            "AND uid >= :minWindowUid AND id NOT IN (:keepIds)",
    )
    suspend fun deleteSyncedInWindowNotIn(accountId: String, folder: String, minWindowUid: Long, keepIds: List<String>)

    /** Lowest cached UID among an account's synced rows in [folder] — the backfill boundary. Null if none. */
    @Query("SELECT MIN(uid) FROM messages WHERE accountId = :accountId AND folder = :folder AND inInbox = 1")
    suspend fun lowestSyncedUid(accountId: String, folder: String): Long?

    /** Number of an account's synced rows in [folder] (count-based retention floor / prune sizing). */
    @Query("SELECT COUNT(*) FROM messages WHERE accountId = :accountId AND folder = :folder AND inInbox = 1")
    suspend fun countSynced(accountId: String, folder: String): Int

    /** Oldest cached timestamp among an account's synced rows in [folder] (age-based retention floor). Null if none. */
    @Query(
        "SELECT MIN(timestampMillis) FROM messages " +
            "WHERE accountId = :accountId AND folder = :folder AND inInbox = 1",
    )
    suspend fun oldestSyncedTimestamp(accountId: String, folder: String): Long?

    /** Distinct folders that have at least one synced row for [accountId] (backfill/prune targets). */
    @Query("SELECT DISTINCT folder FROM messages WHERE accountId = :accountId AND inInbox = 1")
    suspend fun syncedFolders(accountId: String): List<String>

    /** Ids of an account's synced rows older than [cutoffMillis] (age-based prune candidates, all folders). */
    @Query("SELECT id FROM messages WHERE accountId = :accountId AND inInbox = 1 AND timestampMillis < :cutoffMillis")
    suspend fun syncedIdsOlderThan(accountId: String, cutoffMillis: Long): List<String>

    /**
     * Ids of an account's synced rows in [folder] beyond the newest [keep] by recency (count-based
     * prune candidates). Ties broken by UID so the boundary is deterministic.
     */
    @Query(
        "SELECT id FROM messages WHERE accountId = :accountId AND folder = :folder AND inInbox = 1 " +
            "AND id NOT IN (" +
            "SELECT id FROM messages WHERE accountId = :accountId AND folder = :folder AND inInbox = 1 " +
            "ORDER BY timestampMillis DESC, uid DESC LIMIT :keep)",
    )
    suspend fun syncedIdsBeyondCountInFolder(accountId: String, folder: String, keep: Int): List<String>

    /** Removes transient server-search hits (called when search closes). */
    @Query("DELETE FROM messages WHERE inInbox = 0")
    suspend fun deleteSearchRows()
}
