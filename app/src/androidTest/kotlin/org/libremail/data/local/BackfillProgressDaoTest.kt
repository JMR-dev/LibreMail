// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.data.local.dao.BackfillProgressDao
import org.libremail.data.local.entity.BackfillProgressEntity

/**
 * Real-SQLite behavior of [BackfillProgressDao] — the per-(account, folder) paging boundary the
 * full-history backfill persists so it resumes after process death: point read, upsert-on-conflict,
 * account-scoped clear, and the global reset used when the retention default changes.
 */
@RunWith(AndroidJUnit4::class)
class BackfillProgressDaoTest {

    private lateinit var db: LibreMailDatabase
    private lateinit var dao: BackfillProgressDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LibreMailDatabase::class.java).build()
        dao = db.backfillProgressDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun getReturnsTheStoredBoundaryOrNull() = runBlocking {
        dao.upsert(BackfillProgressEntity("acct", "INBOX", nextBeforeUid = 42, complete = false))

        val stored = dao.get("acct", "INBOX")
        assertEquals(42L, stored?.nextBeforeUid)
        assertEquals(false, stored?.complete)
        assertNull(dao.get("acct", "Archive"))
    }

    @Test
    fun upsertReplacesTheBoundaryForTheSameAccountAndFolder() = runBlocking {
        dao.upsert(BackfillProgressEntity("acct", "INBOX", nextBeforeUid = 100, complete = false))

        // A later page lowers the boundary and can mark the folder complete.
        dao.upsert(BackfillProgressEntity("acct", "INBOX", nextBeforeUid = 10, complete = true))

        val stored = dao.get("acct", "INBOX")
        assertEquals(10L, stored?.nextBeforeUid)
        assertEquals(true, stored?.complete)
    }

    @Test
    fun deleteForAccountClearsOnlyThatAccountsProgress() = runBlocking {
        dao.upsert(BackfillProgressEntity("acct", "INBOX", nextBeforeUid = 1))
        dao.upsert(BackfillProgressEntity("acct", "Archive", nextBeforeUid = 2))
        dao.upsert(BackfillProgressEntity("acct2", "INBOX", nextBeforeUid = 3))

        dao.deleteForAccount("acct")

        assertNull(dao.get("acct", "INBOX"))
        assertNull(dao.get("acct", "Archive"))
        assertEquals(3L, dao.get("acct2", "INBOX")?.nextBeforeUid)
    }

    @Test
    fun deleteAllClearsEveryAccountsProgress() = runBlocking {
        dao.upsert(BackfillProgressEntity("acct", "INBOX", nextBeforeUid = 1))
        dao.upsert(BackfillProgressEntity("acct2", "INBOX", nextBeforeUid = 2))

        dao.deleteAll()

        assertNull(dao.get("acct", "INBOX"))
        assertNull(dao.get("acct2", "INBOX"))
    }
}
