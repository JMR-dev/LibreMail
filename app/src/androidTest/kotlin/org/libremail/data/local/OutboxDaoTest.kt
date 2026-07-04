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
import org.libremail.data.local.dao.OutboxDao
import org.libremail.data.local.entity.OutboxEntity

/**
 * Real-SQLite behavior of [OutboxDao] — the queue the send worker drains: FIFO ordering by
 * `createdAt`, point lookups, the live list/count observers, the last-error mutator, and row
 * deletion on successful send.
 */
@RunWith(AndroidJUnit4::class)
class OutboxDaoTest {

    private lateinit var db: LibreMailDatabase
    private lateinit var dao: OutboxDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LibreMailDatabase::class.java).build()
        dao = db.outboxDao()
    }

    @After
    fun tearDown() = db.close()

    private fun outbox(id: String, createdAt: Long = 1_000L) = OutboxEntity(
        id = id,
        accountId = "acct",
        toAddresses = "bob@example.org",
        ccAddresses = "",
        subject = "Subject $id",
        body = "Body",
        createdAt = createdAt,
    )

    @Test
    fun getAllReturnsQueuedMessagesOldestFirst() = runBlocking {
        dao.insert(outbox("late", createdAt = 300))
        dao.insert(outbox("early", createdAt = 100))
        dao.insert(outbox("middle", createdAt = 200))

        assertEquals(listOf("early", "middle", "late"), dao.getAll().map { it.id })
    }

    @Test
    fun getByIdReturnsTheRowOrNull() = runBlocking {
        dao.insert(outbox("out-1"))

        assertEquals("Subject out-1", dao.getById("out-1")?.subject)
        assertNull(dao.getById("absent"))
    }

    @Test
    fun observeAllAndObserveCountReflectTheQueue() = runBlocking {
        dao.insert(outbox("out-1", createdAt = 100))
        dao.insert(outbox("out-2", createdAt = 200))

        assertEquals(listOf("out-1", "out-2"), dao.observeAll().first().map { it.id })
        assertEquals(2, dao.observeCount().first())
    }

    @Test
    fun setErrorRecordsAndThenClearsTheLastError() = runBlocking {
        dao.insert(outbox("out-1"))

        dao.setError("out-1", "SMTP 550")
        assertEquals("SMTP 550", dao.getById("out-1")?.lastError)

        dao.setError("out-1", null)
        assertNull("a successful retry clears the error", dao.getById("out-1")?.lastError)
    }

    @Test
    fun deleteRemovesTheSentRow() = runBlocking {
        dao.insert(outbox("out-1"))
        dao.insert(outbox("out-2"))

        dao.delete("out-1")

        assertEquals(listOf("out-2"), dao.getAll().map { it.id })
        assertEquals(1, dao.observeCount().first())
    }
}
