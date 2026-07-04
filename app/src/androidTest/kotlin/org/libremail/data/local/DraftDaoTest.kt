// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.data.local.dao.DraftDao
import org.libremail.data.local.entity.DraftEntity

/**
 * Real-SQLite behavior of [DraftDao] — the saved-but-unsent store the compose autosave writes:
 * newest-first observation by `updatedAt`, the live count, point/bulk reads, upsert-on-conflict, and
 * deletion.
 */
@RunWith(AndroidJUnit4::class)
class DraftDaoTest {

    private lateinit var db: LibreMailDatabase
    private lateinit var dao: DraftDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LibreMailDatabase::class.java).build()
        dao = db.draftDao()
    }

    @After
    fun tearDown() = db.close()

    private fun draft(id: String, updatedAt: Long = 1_000L, subject: String = "Draft $id") = DraftEntity(
        id = id,
        accountId = "acct",
        toAddresses = "bob@example.org",
        ccAddresses = "",
        subject = subject,
        body = "Body",
        updatedAt = updatedAt,
    )

    @Test
    fun observeAllReturnsDraftsNewestFirstAndObserveCountTracksThem() = runBlocking {
        dao.upsert(draft("old", updatedAt = 100))
        dao.upsert(draft("new", updatedAt = 300))
        dao.upsert(draft("mid", updatedAt = 200))

        assertEquals(listOf("new", "mid", "old"), dao.observeAll().first().map { it.id })
        assertEquals(3, dao.observeCount().first())
    }

    @Test
    fun getByIdAndGetAllReadStoredDrafts() = runBlocking {
        dao.upsert(draft("d1"))
        dao.upsert(draft("d2"))

        assertEquals("Draft d1", dao.getById("d1")?.subject)
        assertNull(dao.getById("absent"))
        assertEquals(setOf("d1", "d2"), dao.getAll().map { it.id }.toSet())
    }

    @Test
    fun upsertReplacesADraftWithTheSameId() = runBlocking {
        dao.upsert(draft("d1", subject = "First"))

        dao.upsert(draft("d1", subject = "Edited"))

        assertEquals("Edited", dao.getById("d1")?.subject)
        assertEquals(1, dao.observeCount().first())
    }

    @Test
    fun deleteRemovesTheDraft() = runBlocking {
        dao.upsert(draft("d1"))
        dao.upsert(draft("d2"))

        dao.delete("d1")

        assertEquals(listOf("d2"), dao.getAll().map { it.id })
    }
}
