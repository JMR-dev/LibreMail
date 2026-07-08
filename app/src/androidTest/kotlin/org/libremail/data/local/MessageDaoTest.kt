// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.data.local.dao.MessageDao
import org.libremail.data.local.entity.MessageEntity
import org.libremail.data.local.entity.MessageSummary

/**
 * Real-SQLite behavior of the [MessageDao] query and mutation surface not already pinned by
 * [MessageDaoRetentionTest] (the retention/backfill boundary probes) or [MessageDaoRoutingTest] (the
 * body-less routing projection): the Paging 3 browse/search sources, the flag/body/header mutators,
 * and the scoped deletes. Exercised against a real in-memory database so the generated SQL — and its
 * `inInbox`/folder/account scoping and ordering — runs for real.
 */
@RunWith(AndroidJUnit4::class)
class MessageDaoTest {

    private lateinit var db: LibreMailDatabase
    private lateinit var dao: MessageDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LibreMailDatabase::class.java).build()
        dao = db.messageDao()
    }

    @After
    fun tearDown() = db.close()

    @Suppress("LongParameterList")
    private fun message(
        id: String,
        accountId: String = "acct",
        folder: String = "INBOX",
        subject: String = "Hi",
        sender: String = "Ada",
        senderEmail: String = "ada@example.org",
        snippet: String = "",
        body: String = "",
        timestampMillis: Long = 1_000L,
        isRead: Boolean = false,
        isStarred: Boolean = false,
        inInbox: Boolean = true,
        bodyFetched: Boolean = false,
        isHtml: Boolean = false,
        uid: Long = 0L,
    ) = MessageEntity(
        id = id,
        accountId = accountId,
        sender = sender,
        senderEmail = senderEmail,
        subject = subject,
        snippet = snippet,
        body = body,
        isHtml = isHtml,
        timestampMillis = timestampMillis,
        isRead = isRead,
        isStarred = isStarred,
        folder = folder,
        inInbox = inInbox,
        bodyFetched = bodyFetched,
        uid = uid,
        // Mirror production's Unicode-casefold population (issue #232): Mappers.toEntity + MessageDao's
        // updateHeaderContent/updateBody write `lowercase()` copies of the searchable fields, and the
        // *SearchSummaries queries match against these `*Fold` columns — so fixtures must set them too.
        senderFold = sender.lowercase(),
        senderEmailFold = senderEmail.lowercase(),
        subjectFold = subject.lowercase(),
        snippetFold = snippet.lowercase(),
    )

    /** Refreshes a [PagingSource] and returns the first loaded page's ids in order. */
    private suspend fun PagingSource<Int, MessageSummary>.refreshIds(loadSize: Int = 20): List<String> {
        val result = load(PagingSource.LoadParams.Refresh(key = null, loadSize = loadSize, placeholdersEnabled = false))
        return (result as PagingSource.LoadResult.Page).data.map { it.id }
    }

    @Test
    fun pagingUnifiedFolderSummariesReturnsSyncedRowsNewestFirstAcrossAccounts() = runBlocking {
        dao.insertNew(
            listOf(
                message("acct:INBOX:1", timestampMillis = 100),
                message("acct:INBOX:2", timestampMillis = 300),
                message("acct2:INBOX:3", accountId = "acct2", timestampMillis = 200),
                message("acct:INBOX:search", timestampMillis = 999, inInbox = false), // search-only excluded
                message("acct:Archive:1", folder = "Archive", timestampMillis = 500), // other folder excluded
            ),
        )

        // Newest-first by timestamp, both accounts' INBOX rows, no search-only row, no other folder.
        assertEquals(
            listOf("acct:INBOX:2", "acct2:INBOX:3", "acct:INBOX:1"),
            dao.pagingUnifiedFolderSummaries("INBOX").refreshIds(),
        )
    }

    @Test
    fun pagingFolderSummariesIsScopedToOneAccountAndFolder() = runBlocking {
        dao.insertNew(
            listOf(
                message("acct:INBOX:1", timestampMillis = 100),
                message("acct:INBOX:2", timestampMillis = 200),
                message("acct2:INBOX:3", accountId = "acct2", timestampMillis = 300), // other account
                message("acct:Archive:1", folder = "Archive", timestampMillis = 400), // other folder
                message("acct:INBOX:s", timestampMillis = 999, inInbox = false), // search-only
            ),
        )

        assertEquals(
            listOf("acct:INBOX:2", "acct:INBOX:1"),
            dao.pagingFolderSummaries("acct", "INBOX").refreshIds(),
        )
    }

    @Test
    fun pagingUnifiedFolderSearchSummariesMatchesEveryScannedColumnAndSurfacesSearchRows() = runBlocking {
        dao.insertNew(
            listOf(
                message("bySubject", subject = "Quarterly report", timestampMillis = 100),
                message("bySender", sender = "Reporter", subject = "x", timestampMillis = 200),
                message("bySenderEmail", senderEmail = "report@x.org", subject = "x", timestampMillis = 300),
                message("bySnippet", snippet = "see the report", subject = "x", timestampMillis = 400),
                message("searchHit", subject = "report", timestampMillis = 500, inInbox = false), // surfaced
                message("noMatch", subject = "unrelated", timestampMillis = 600),
                message("otherFolder", subject = "report", folder = "Archive", timestampMillis = 700),
            ),
        )

        // Unified search matches sender/senderEmail/subject/snippet, includes transient inInbox=0 hits,
        // and is folder-scoped. Newest-first.
        assertEquals(
            listOf("searchHit", "bySnippet", "bySenderEmail", "bySender", "bySubject"),
            dao.pagingUnifiedFolderSearchSummaries("INBOX", "%report%").refreshIds(),
        )
    }

    @Test
    fun pagingFolderSearchSummariesIsAccountScopedAndSurfacesSearchRows() = runBlocking {
        dao.insertNew(
            listOf(
                message("mine", subject = "the report", timestampMillis = 100),
                message("mineSearch", subject = "report", timestampMillis = 200, inInbox = false),
                message("theirs", subject = "report", accountId = "acct2", timestampMillis = 300),
            ),
        )

        assertEquals(
            listOf("mineSearch", "mine"),
            dao.pagingFolderSearchSummaries("acct", "INBOX", "%report%").refreshIds(),
        )
    }

    @Test
    fun browsePagingBreaksTimestampTiesByAscendingIdForATotalPageOrder() = runBlocking {
        // Bulk mail can share a timestamp (issue #311): without a unique tiebreaker, rows tied at a
        // LIMIT/OFFSET page boundary can duplicate or skip. Insertion order is scrambled so the ORDER BY
        // — not the storage order — must produce the result.
        dao.insertNew(
            listOf(
                message("tie-c", timestampMillis = 1_000),
                message("tie-a", timestampMillis = 1_000),
                message("tie-b", timestampMillis = 1_000),
                message("newer", timestampMillis = 2_000),
            ),
        )

        // Newest timestamp first, then ties broken by ascending id — a deterministic total order.
        val expected = listOf("newer", "tie-a", "tie-b", "tie-c")
        assertEquals(expected, dao.pagingUnifiedFolderSummaries("INBOX").refreshIds())
        assertEquals(expected, dao.pagingFolderSummaries("acct", "INBOX").refreshIds())
    }

    @Test
    fun searchPagingBreaksTimestampTiesByAscendingIdForATotalPageOrder() = runBlocking {
        dao.insertNew(
            listOf(
                message("hit-c", subject = "report", timestampMillis = 1_000),
                message("hit-a", subject = "report", timestampMillis = 1_000),
                message("hit-b", subject = "report", timestampMillis = 1_000),
            ),
        )

        val expected = listOf("hit-a", "hit-b", "hit-c")
        assertEquals(expected, dao.pagingUnifiedFolderSearchSummaries("INBOX", "%report%").refreshIds())
        assertEquals(expected, dao.pagingFolderSearchSummaries("acct", "INBOX", "%report%").refreshIds())
    }

    @Test
    fun getUnfetchedIdsReturnsOnlySyncedRowsMissingABody() = runBlocking {
        dao.insertNew(
            listOf(
                message("unfetched", bodyFetched = false),
                message("fetched", bodyFetched = true),
                message("searchUnfetched", bodyFetched = false, inInbox = false), // not synced
                message("otherFolder", folder = "Archive", bodyFetched = false), // different folder
            ),
        )

        assertEquals(listOf("unfetched"), dao.getUnfetchedIds("acct", "INBOX"))
    }

    @Test
    fun insertNewIgnoresConflictsAndLeavesExistingRowsIntact() = runBlocking {
        dao.insertNew(listOf(message("acct:1", subject = "Original", isRead = true)))

        // A re-insert of the same id (e.g. the next sync re-listing it) must NOT clobber the cached row.
        dao.insertNew(listOf(message("acct:1", subject = "Replaced", isRead = false)))

        val row = dao.getById("acct:1")
        assertEquals("Original", row?.subject)
        assertEquals(true, row?.isRead)
    }

    @Test
    fun existingIdsReturnsOnlyThoseAlreadyStored() = runBlocking {
        dao.insertNew(listOf(message("acct:1"), message("acct:2")))

        assertEquals(
            setOf("acct:1", "acct:2"),
            dao.existingIds(listOf("acct:1", "acct:2", "acct:absent")).toSet(),
        )
    }

    @Test
    fun updateHeaderContentRefreshesDisplayFieldsAndUidOnly() = runBlocking {
        dao.insertNew(
            listOf(
                message("acct:1", isRead = true, isStarred = true, inInbox = true, body = "cached", uid = 0),
            ),
        )

        dao.updateHeaderContent(
            id = "acct:1",
            sender = "Charles",
            senderEmail = "charles@example.org",
            subject = "Refreshed",
            timestampMillis = 5_000L,
            uid = 42L,
        )

        val row = requireNotNull(dao.getById("acct:1"))
        assertEquals("Charles", row.sender)
        assertEquals("charles@example.org", row.senderEmail)
        assertEquals("Refreshed", row.subject)
        assertEquals(5_000L, row.timestampMillis)
        assertEquals(42L, row.uid)
        // Local flags, the cached body, and inbox membership are deliberately left untouched.
        assertEquals(true, row.isRead)
        assertEquals(true, row.isStarred)
        assertEquals("cached", row.body)
        assertEquals(true, row.inInbox)
    }

    @Test
    fun updateHeaderContentsRefreshesEveryRowInTheBatchAndLeavesFlagsAndBodiesUntouched() = runBlocking {
        dao.insertNew(
            listOf(
                message("acct:1", isRead = true, isStarred = false, body = "cached-1", uid = 0),
                message("acct:2", isRead = false, isStarred = true, body = "cached-2", uid = 0),
            ),
        )

        // The sync path (issue #310) refreshes a whole recent window at once via this single-transaction
        // batch. Only the six header fields of each passed entity are applied; the rest are ignored.
        dao.updateHeaderContents(
            listOf(
                message(
                    "acct:1",
                    sender = "Charles",
                    senderEmail = "charles@example.org",
                    subject = "One",
                    timestampMillis = 5_000L,
                    uid = 42L,
                ),
                message(
                    "acct:2",
                    sender = "Grace",
                    senderEmail = "grace@example.org",
                    subject = "Two",
                    timestampMillis = 6_000L,
                    uid = 43L,
                ),
            ),
        )

        val one = requireNotNull(dao.getById("acct:1"))
        assertEquals("Charles", one.sender)
        assertEquals("charles@example.org", one.senderEmail)
        assertEquals("One", one.subject)
        assertEquals(5_000L, one.timestampMillis)
        assertEquals(42L, one.uid)
        // The casefold search columns track the refreshed headers (issue #232).
        assertEquals("charles", one.senderFold)
        assertEquals("one", one.subjectFold)
        // Flags and the cached body are deliberately left untouched.
        assertTrue(one.isRead)
        assertEquals("cached-1", one.body)

        val two = requireNotNull(dao.getById("acct:2"))
        assertEquals("Grace", two.sender)
        assertEquals("Two", two.subject)
        assertEquals(43L, two.uid)
        assertTrue(two.isStarred)
        assertEquals("cached-2", two.body)
    }

    /**
     * The batched [MessageDao.updateHeaderContents] must write byte-for-byte the same row as the per-row
     * [MessageDao.updateHeaderContent] it replaces on the backfill path (issue #322): the same refreshed
     * fields and casefold columns, and the same untouched flags/body/membership. Two rows seeded
     * identically and refreshed by the two paths with the same values must end up identical.
     */
    @Test
    fun updateHeaderContentsWritesTheSameResultAsThePerRowUpdate() = runBlocking {
        dao.insertNew(
            listOf(
                message("perRow", isStarred = true, body = "cached"),
                message("batch", isStarred = true, body = "cached"),
            ),
        )

        // Old path: the single-row update. New path: the batch, carrying identical field values.
        dao.updateHeaderContent(
            id = "perRow",
            sender = "Refreshed",
            senderEmail = "refreshed@example.org",
            subject = "Fresh",
            timestampMillis = 9_000L,
            uid = 7L,
        )
        dao.updateHeaderContents(
            listOf(
                message(
                    "batch",
                    sender = "Refreshed",
                    senderEmail = "refreshed@example.org",
                    subject = "Fresh",
                    timestampMillis = 9_000L,
                    uid = 7L,
                ),
            ),
        )

        // Every stored column — refreshed and preserved alike — matches between the two paths.
        val perRow = requireNotNull(dao.getById("perRow"))
        val batch = requireNotNull(dao.getById("batch"))
        assertEquals(perRow.copy(id = "id"), batch.copy(id = "id"))
    }

    /** An empty batch is a no-op — issue #322's empty-batch boundary at the DAO transaction level. */
    @Test
    fun updateHeaderContentsWithAnEmptyBatchWritesNothing() = runBlocking {
        dao.insertNew(listOf(message("acct:1", subject = "Original", isRead = true, uid = 5)))

        dao.updateHeaderContents(emptyList())

        val row = requireNotNull(dao.getById("acct:1"))
        assertEquals("Original", row.subject)
        assertEquals(5L, row.uid)
        assertTrue(row.isRead)
    }

    @Test
    fun markSyncedPromotesSearchOnlyRowsIntoTheFolder() = runBlocking {
        dao.insertNew(
            listOf(
                message("promote", inInbox = false),
                message("leaveAlone", inInbox = false),
            ),
        )

        dao.markSynced(listOf("promote"))

        assertEquals(listOf("promote"), dao.getSyncedIds("acct", "INBOX"))
        assertFalse(dao.getById("leaveAlone")!!.inInbox)
    }

    @Test
    fun updateBodyStoresBodyHtmlSnippetAndMarksFetched() = runBlocking {
        dao.insertNew(listOf(message("acct:1", bodyFetched = false)))

        dao.updateBody("acct:1", body = "<p>Hello</p>", isHtml = true, snippet = "Hello")

        val row = requireNotNull(dao.getById("acct:1"))
        assertEquals("<p>Hello</p>", row.body)
        assertTrue(row.isHtml)
        assertEquals("Hello", row.snippet)
        assertTrue(row.bodyFetched)
    }

    @Test
    fun setReadAndSetStarredToggleOnlyTheirFlag() = runBlocking {
        dao.insertNew(listOf(message("acct:1", isRead = false, isStarred = false)))

        dao.setRead("acct:1", true)
        dao.setStarred("acct:1", true)

        val row = requireNotNull(dao.getById("acct:1"))
        assertTrue(row.isRead)
        assertTrue(row.isStarred)
    }

    @Test
    fun deleteByIdsRemovesExactlyTheGivenRows() = runBlocking {
        dao.insertNew(listOf(message("a"), message("b"), message("c")))

        dao.deleteByIds(listOf("a", "c"))

        assertEquals(listOf("b"), dao.observeSummaries().first().map { it.id })
    }

    @Test
    fun deleteByAccountRemovesEveryRowOfThatAccountOnly() = runBlocking {
        dao.insertNew(
            listOf(
                message("acct:1"),
                message("acct:Archive:1", folder = "Archive"),
                message("acct2:1", accountId = "acct2"),
            ),
        )

        dao.deleteByAccount("acct")

        assertEquals(listOf("acct2:1"), dao.observeSummaries().first().map { it.id })
    }

    @Test
    fun deleteSyncedByAccountFolderSparesSearchRowsAndOtherFolders() = runBlocking {
        dao.insertNew(
            listOf(
                message("synced", inInbox = true),
                message("searchOnly", inInbox = false),
                message("otherFolder", folder = "Archive"),
                message("otherAccount", accountId = "acct2"),
            ),
        )

        dao.deleteSyncedByAccountFolder("acct", "INBOX")

        assertNull("the synced INBOX row is deleted", dao.getById("synced"))
        assertTrue("a transient search-only row is spared", dao.getById("searchOnly") != null)
        assertTrue("another folder is untouched", dao.getById("otherFolder") != null)
        assertTrue("another account is untouched", dao.getById("otherAccount") != null)
    }
}
