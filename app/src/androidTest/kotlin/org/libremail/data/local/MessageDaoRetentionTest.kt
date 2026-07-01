// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.data.local.dao.MessageDao
import org.libremail.data.local.entity.MessageEntity

/**
 * Real-SQLite behavior of the retention / backfill boundary queries on [MessageDao] (issues #12/#13).
 *
 * These queries *define* the device-only retention floor — the pruner deletes below it
 * ([MessageDao.syncedIdsBeyondCountInFolder] / [MessageDao.syncedIdsOlderThan]) and the backfiller
 * stops above it ([MessageDao.lowestSyncedUid] / [MessageDao.countSynced] /
 * [MessageDao.oldestSyncedTimestamp]) — so the whole "backfill and prune never fight over the same
 * rows" guarantee rests on their SQL. The [org.libremail.data.sync.MailPruner] /
 * [org.libremail.data.sync.MailBackfiller] unit tests mock the DAO, so the `ORDER BY … DESC LIMIT`
 * newest-N selection, the strict age cutoff, and the windowed reconcile that spares backfilled history
 * are exercised here against a real database instead.
 */
@RunWith(AndroidJUnit4::class)
class MessageDaoRetentionTest {

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

    private fun message(
        id: String,
        accountId: String = "acct",
        folder: String = "INBOX",
        uid: Long = 0L,
        timestampMillis: Long = 1_000L,
        inInbox: Boolean = true,
    ) = MessageEntity(
        id = id,
        accountId = accountId,
        sender = "Ada",
        senderEmail = "ada@example.org",
        subject = "Hi",
        snippet = "",
        body = "",
        timestampMillis = timestampMillis,
        isRead = false,
        isStarred = false,
        folder = folder,
        inInbox = inInbox,
        uid = uid,
    )

    /**
     * The count-based prune boundary keeps the newest [keep] by recency and returns the REST for
     * deletion, breaking timestamp ties by the higher UID. This pins the ordering *direction* (a
     * flipped `DESC` would keep the OLDEST rows — i.e. locally delete the user's most recent mail) and
     * the tie-break, neither of which the mocked-DAO unit tests can catch.
     */
    @Test
    fun syncedIdsBeyondCountInFolderKeepsNewestByTimestampThenUid() = runBlocking {
        dao.insertNew(
            listOf(
                message("A", uid = 30, timestampMillis = 300), // newest
                message("B", uid = 25, timestampMillis = 200), // ties C on timestamp; higher uid => newer
                message("C", uid = 20, timestampMillis = 200),
                message("D", uid = 10, timestampMillis = 100), // oldest
                // Scoping decoys: a search-only row and another folder must never enter the ranking.
                message("SR", uid = 99, timestampMillis = 999, inInbox = false),
                message("AR", uid = 5, timestampMillis = 50, folder = "Archive"),
            ),
        )

        // Keep the newest 2 (A, B); the rest are prunable. B is kept over C purely by the uid tie-break.
        assertEquals(
            setOf("C", "D"),
            dao.syncedIdsBeyondCountInFolder("acct", "INBOX", keep = 2).toSet(),
        )
        // Keeping at least as many as exist prunes nothing.
        assertEquals(emptyList<String>(), dao.syncedIdsBeyondCountInFolder("acct", "INBOX", keep = 4))
    }

    /**
     * The age-based prune boundary selects rows STRICTLY older than the cutoff, across every folder,
     * scoped to the account and to synced (non-search) rows only.
     */
    @Test
    fun syncedIdsOlderThanCutsStrictlyBelowAcrossFoldersAndScopesToAccount() = runBlocking {
        dao.insertNew(
            listOf(
                message("boundary", uid = 25, timestampMillis = 200), // == cutoff -> kept (strict `<`)
                message("old-inbox", uid = 10, timestampMillis = 100),
                message("old-archive", uid = 5, timestampMillis = 150, folder = "Archive"),
                message("old-search", uid = 1, timestampMillis = 1, inInbox = false), // not synced
                message("old-other-account", accountId = "acct2", uid = 1, timestampMillis = 1),
            ),
        )

        assertEquals(
            setOf("old-inbox", "old-archive"),
            dao.syncedIdsOlderThan("acct", cutoffMillis = 200).toSet(),
        )
    }

    /**
     * The windowed reconcile deletes only synced rows at/above the recent-UID window that the server no
     * longer returns; older backfilled history (below the window), other folders, and search rows are
     * left intact — the core guarantee that a foreground sync no longer wipes backfilled history (#12).
     */
    @Test
    fun deleteSyncedInWindowNotInSparesBelowWindowHistoryAndOtherFolders() = runBlocking {
        dao.insertNew(
            listOf(
                message("below", uid = 10), // below the window -> spared
                message("kept", uid = 20), // in window, in keep set -> spared
                message("gone-1", uid = 30), // in window, not kept -> deleted
                message("gone-2", uid = 40), // in window, not kept -> deleted
                message("search", uid = 22, inInbox = false), // not synced -> spared
                message("other-folder", uid = 25, folder = "Archive"), // different folder -> spared
            ),
        )

        dao.deleteSyncedInWindowNotIn("acct", "INBOX", minWindowUid = 20, keepIds = listOf("kept"))

        // Read survivors back with point lookups (getById) rather than observeAll(): explicit about each
        // row's fate, and it keeps the assertion off the Flow API.
        assertNull("gone-1 is in-window and unkept -> deleted", dao.getById("gone-1"))
        assertNull("gone-2 is in-window and unkept -> deleted", dao.getById("gone-2"))
        assertNotNull("below-window history must survive", dao.getById("below"))
        assertNotNull("the kept row must survive", dao.getById("kept"))
        assertNotNull("search rows are not synced -> untouched", dao.getById("search"))
        assertNotNull("other folders are untouched", dao.getById("other-folder"))
    }

    /**
     * The backfiller's floor probes reflect only an account's synced rows in the given folder, and are
     * null/zero for a folder with nothing cached (so the backfiller then starts from `Long.MAX_VALUE`).
     */
    @Test
    fun floorProbesReflectOnlySyncedRowsInTheFolder() = runBlocking {
        dao.insertNew(
            listOf(
                message("a", uid = 30, timestampMillis = 300),
                message("d", uid = 10, timestampMillis = 100),
                message("search", uid = 1, timestampMillis = 1, inInbox = false), // excluded
                message("archive", uid = 5, timestampMillis = 50, folder = "Archive"), // different folder
            ),
        )

        assertEquals(10L, dao.lowestSyncedUid("acct", "INBOX"))
        assertEquals(2, dao.countSynced("acct", "INBOX"))
        assertEquals(100L, dao.oldestSyncedTimestamp("acct", "INBOX"))

        assertNull(dao.lowestSyncedUid("acct", "Nonexistent"))
        assertEquals(0, dao.countSynced("acct", "Nonexistent"))
        assertNull(dao.oldestSyncedTimestamp("acct", "Nonexistent"))
    }

    /** Backfill / prune enumerate their targets via [syncedFolders]: distinct synced folders, per account. */
    @Test
    fun syncedFoldersReturnsDistinctSyncedFoldersForTheAccount() = runBlocking {
        dao.insertNew(
            listOf(
                message("i1", folder = "INBOX"),
                message("i2", folder = "INBOX"),
                message("ar", folder = "Archive"),
                message("sr", folder = "Search", inInbox = false), // search-only folder excluded
                message("other", accountId = "acct2", folder = "Spam"), // other account excluded
            ),
        )

        assertEquals(setOf("INBOX", "Archive"), dao.syncedFolders("acct").toSet())
    }
}
