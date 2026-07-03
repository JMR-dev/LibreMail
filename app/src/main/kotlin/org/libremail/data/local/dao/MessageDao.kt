// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.libremail.data.local.entity.FolderUnreadCount
import org.libremail.data.local.entity.MessageEntity
import org.libremail.data.local.entity.MessageRouting
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

    /**
     * Paged unified-inbox projection: folder-synced rows of [folder] across every account,
     * newest-first, as a Paging 3 [PagingSource] (issue #124). Room loads only the requested window
     * (LIMIT/OFFSET), so the mailbox list's query, mapping, and recomposition cost scale with what's on
     * screen, not the whole cache — instead of materializing the *entire* unified inbox (~thousands of
     * rows) on every emission. Filters `inInbox = 1` because the paged browse list shows only synced
     * rows; unified *search* (which must also surface transient `inInbox = 0` hits) is paged separately
     * by [pagingUnifiedFolderSearchSummaries] (issue #214). Profiling (see
     * `docs/perf/issue-124-unified-inbox-paging.md`) showed the first page loads flat regardless of
     * total cache size on the existing indices, so no `(folder, …)` index / schema migration is added.
     */
    @Query(
        "SELECT id, accountId, sender, senderEmail, subject, snippet, timestampMillis, " +
            "isRead, isStarred, folder, inInbox, bodyFetched FROM messages " +
            "WHERE folder = :folder AND inInbox = 1 ORDER BY timestampMillis DESC",
    )
    fun pagingUnifiedFolderSummaries(folder: String): PagingSource<Int, MessageSummary>

    /**
     * Paged per-account folder projection: [accountId]'s folder-synced rows of [folder], newest-first,
     * as a Paging 3 [PagingSource] (issue #214). The account-scoped counterpart of
     * [pagingUnifiedFolderSummaries] — brings #124's window-at-a-time loading to the per-account browse
     * list so opening a large account folder no longer materializes the whole folder into memory.
     * Filters `inInbox = 1` (synced browse rows only; per-account *search* uses
     * [pagingFolderSearchSummaries], which also surfaces transient `inInbox = 0` hits). Served by the
     * `(accountId, folder, uid)` index's `(accountId, folder)` prefix, so no new index / migration.
     */
    @Query(
        "SELECT id, accountId, sender, senderEmail, subject, snippet, timestampMillis, " +
            "isRead, isStarred, folder, inInbox, bodyFetched FROM messages " +
            "WHERE accountId = :accountId AND folder = :folder AND inInbox = 1 ORDER BY timestampMillis DESC",
    )
    fun pagingFolderSummaries(accountId: String, folder: String): PagingSource<Int, MessageSummary>

    /**
     * Paged unified search: rows of [folder] across every account whose sender, sender address,
     * subject, or snippet match [pattern] — a pre-built SQL `LIKE` pattern (`%term%`, with the LIKE
     * metacharacters `\ % _` escaped by `\`) — newest-first, as a Paging 3 [PagingSource] (issue #214).
     * Scans the same columns the old in-memory search filter (`matchesSearch`) did, but in SQL so a
     * search over a large folder loads only the visible window. Unlike the browse pagers this does
     * *not* filter `inInbox`, so it surfaces both synced rows and the transient `inInbox = 0`
     * server-search hits `MailRepository.searchServer` inserts — exactly what the old filter saw.
     * Matches the Unicode-casefolded `*Fold` columns (issue #232) with a pattern built from the
     * lowercased query, so search is case-insensitive beyond ASCII — unlike the old ASCII-only `LIKE`.
     */
    @Query(
        "SELECT id, accountId, sender, senderEmail, subject, snippet, timestampMillis, " +
            "isRead, isStarred, folder, inInbox, bodyFetched FROM messages " +
            "WHERE folder = :folder AND (senderFold LIKE :pattern ESCAPE '\\' OR " +
            "senderEmailFold LIKE :pattern ESCAPE '\\' OR subjectFold LIKE :pattern ESCAPE '\\' OR " +
            "snippetFold LIKE :pattern ESCAPE '\\') ORDER BY timestampMillis DESC",
    )
    fun pagingUnifiedFolderSearchSummaries(folder: String, pattern: String): PagingSource<Int, MessageSummary>

    /**
     * Paged per-account search: [accountId]'s rows of [folder] matching [pattern] (see
     * [pagingUnifiedFolderSearchSummaries] for the pattern/column contract), newest-first, as a
     * Paging 3 [PagingSource] (issue #214). Like the unified variant it leaves `inInbox` unfiltered so
     * per-account search still surfaces transient server-search hits.
     */
    @Query(
        "SELECT id, accountId, sender, senderEmail, subject, snippet, timestampMillis, " +
            "isRead, isStarred, folder, inInbox, bodyFetched FROM messages " +
            "WHERE accountId = :accountId AND folder = :folder AND (senderFold LIKE :pattern ESCAPE '\\' OR " +
            "senderEmailFold LIKE :pattern ESCAPE '\\' OR subjectFold LIKE :pattern ESCAPE '\\' OR " +
            "snippetFold LIKE :pattern ESCAPE '\\') ORDER BY timestampMillis DESC",
    )
    fun pagingFolderSearchSummaries(
        accountId: String,
        folder: String,
        pattern: String,
    ): PagingSource<Int, MessageSummary>

    /**
     * Live per-(account, folder) unread counts for the drawer's folder badges and the bold styling of
     * accounts with unread mail. Counts only folder-synced rows (`inInbox = 1`), so transient
     * server-search hits never inflate a badge; read rows and folders with no unread mail are simply
     * absent from the result. A pure `COUNT(*)` aggregate — no message rows are pulled into memory —
     * whose `GROUP BY accountId, folder` is served by the existing `(accountId, folder, uid)` index.
     */
    @Query(
        "SELECT accountId, folder, COUNT(*) AS unreadCount FROM messages " +
            "WHERE inInbox = 1 AND isRead = 0 GROUP BY accountId, folder",
    )
    fun observeUnreadCounts(): Flow<List<FolderUnreadCount>>

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MessageEntity?

    /**
     * Body-less routing/flags projection for a single message (issue #186). The open path and the
     * flag/move callers only need routing and flag columns, so pulling the whole `body` through
     * SQLite's shared CursorWindow on every such read is pure over-fetch — [getById] (`SELECT *`) is
     * reserved for the one read that actually returns the body to the reader. Served by the primary-key
     * lookup, so no new index / migration (mirrors the [MessageSummary] projection).
     */
    @Query(
        "SELECT id, accountId, folder, uid, isRead, isStarred, bodyFetched, isHtml FROM messages " +
            "WHERE id = :id LIMIT 1",
    )
    suspend fun getRouting(id: String): MessageRouting?

    /** Body-less routing/flags projection for a set of messages — batch move/delete/expunge callers. */
    @Query(
        "SELECT id, accountId, folder, uid, isRead, isStarred, bodyFetched, isHtml FROM messages " +
            "WHERE id IN (:ids)",
    )
    suspend fun getRoutingByIds(ids: List<String>): List<MessageRouting>

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

    /** Of the given [ids], those that already have a row — lets a caller refresh only pre-existing rows. */
    @Query("SELECT id FROM messages WHERE id IN (:ids)")
    suspend fun existingIds(ids: List<String>): List<String>

    /**
     * Refreshes the display fields (and the materialized [MessageEntity.uid], keeping it fresh for
     * rows migrated before the column existed) from the server without touching the cached body, the
     * local read/star flags (which may hold an optimistic change the server hasn't reflected yet), or
     * the inbox membership. Keeps the header casefold search columns (issue #232) in sync.
     */
    suspend fun updateHeaderContent(
        id: String,
        sender: String,
        senderEmail: String,
        subject: String,
        timestampMillis: Long,
        uid: Long,
    ) = updateHeaderContentInternal(
        id, sender, senderEmail, subject,
        sender.lowercase(), senderEmail.lowercase(), subject.lowercase(),
        timestampMillis, uid,
    )

    @Query(
        "UPDATE messages SET sender = :sender, senderEmail = :senderEmail, subject = :subject, " +
            "senderFold = :senderFold, senderEmailFold = :senderEmailFold, subjectFold = :subjectFold, " +
            "timestampMillis = :timestampMillis, uid = :uid WHERE id = :id",
    )
    suspend fun updateHeaderContentInternal(
        id: String,
        sender: String,
        senderEmail: String,
        subject: String,
        senderFold: String,
        senderEmailFold: String,
        subjectFold: String,
        timestampMillis: Long,
        uid: Long,
    )

    /** Marks rows as folder-synced (e.g. a former search-only row that the sync now returns). */
    @Query("UPDATE messages SET inInbox = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    /** Sets the fetched body + its derived snippet, keeping [MessageEntity.snippetFold] (search) in sync. */
    suspend fun updateBody(id: String, body: String, isHtml: Boolean, snippet: String) =
        updateBodyInternal(id, body, isHtml, snippet, snippet.lowercase())

    @Query(
        "UPDATE messages SET body = :body, isHtml = :isHtml, snippet = :snippet, " +
            "snippetFold = :snippetFold, bodyFetched = 1 WHERE id = :id",
    )
    suspend fun updateBodyInternal(id: String, body: String, isHtml: Boolean, snippet: String, snippetFold: String)

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

    /**
     * Windowed deletion reconcile for full-history sync (issue #12): within [folder], delete synced
     * rows whose UID falls inside the freshly-fetched recent window (`uid >= minWindowUid`) but which
     * the server no longer returns ([keepIds]). Rows below the window — older history fetched by the
     * background backfill — are deliberately left intact, unlike a whole-folder "not in the recent
     * set" reconcile, which would wipe that backfilled history.
     */
    @Query(
        "DELETE FROM messages WHERE accountId = :accountId AND folder = :folder AND inInbox = 1 " +
            "AND uid >= :minWindowUid AND id NOT IN (:keepIds)",
    )
    suspend fun deleteSyncedInWindowNotIn(accountId: String, folder: String, minWindowUid: Long, keepIds: List<String>)

    /**
     * Lowest cached *resolved* UID among an account's synced rows in [folder] — the backfill
     * boundary. Placeholder rows with `uid <= 0` (a row migrated before the `uid` column existed, or
     * a fetch where the server failed to resolve the UID) are excluded: letting one collapse
     * MIN(uid) to `<= 0` would make the backfiller page below a bound `fetchOlderThan` treats as
     * "nothing older", falsely marking the folder fully backfilled (#95, matching the
     * `minWindowUid` guard in MailSyncer). Null when no resolved-UID row exists, in which case
     * backfill starts over from the newest message.
     */
    @Query(
        "SELECT MIN(uid) FROM messages WHERE accountId = :accountId AND folder = :folder " +
            "AND inInbox = 1 AND uid > 0",
    )
    suspend fun lowestSyncedUid(accountId: String, folder: String): Long?

    /** Number of an account's synced rows in [folder] (count-based retention floor / prune sizing). */
    @Query("SELECT COUNT(*) FROM messages WHERE accountId = :accountId AND folder = :folder AND inInbox = 1")
    suspend fun countSynced(accountId: String, folder: String): Int

    /** Distinct folders that have at least one synced row for [accountId] (backfill/prune targets). */
    @Query("SELECT DISTINCT folder FROM messages WHERE accountId = :accountId AND inInbox = 1")
    suspend fun syncedFolders(accountId: String): List<String>

    /** Ids of an account's synced rows older than [cutoffMillis] (age-based prune candidates, all folders). */
    @Query("SELECT id FROM messages WHERE accountId = :accountId AND inInbox = 1 AND timestampMillis < :cutoffMillis")
    suspend fun syncedIdsOlderThan(accountId: String, cutoffMillis: Long): List<String>

    /**
     * Ids of an account's synced rows in [folder] beyond the newest [keep] by ARRIVAL (server UID —
     * count-based prune candidates). Ordering by UID (not by the Date header) matches the newest-by-UID
     * recent window [org.libremail.mail.ImapClient.fetchRecent] keeps fresh, so a message with a high
     * UID but an old Date isn't re-fetched by every sync and re-pruned by every cycle.
     */
    @Query(
        "SELECT id FROM messages WHERE accountId = :accountId AND folder = :folder AND inInbox = 1 " +
            "AND id NOT IN (" +
            "SELECT id FROM messages WHERE accountId = :accountId AND folder = :folder AND inInbox = 1 " +
            "ORDER BY uid DESC LIMIT :keep)",
    )
    suspend fun syncedIdsBeyondCountInFolder(accountId: String, folder: String, keep: Int): List<String>

    /** Removes transient server-search hits (called when search closes). */
    @Query("DELETE FROM messages WHERE inInbox = 0")
    suspend fun deleteSearchRows()
}
