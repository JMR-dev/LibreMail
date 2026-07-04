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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.data.local.dao.AttachmentDao
import org.libremail.data.local.dao.MessageDao
import org.libremail.data.local.entity.AttachmentEntity
import org.libremail.data.local.entity.MessageEntity

/**
 * Real-SQLite behavior of [AttachmentDao]: the `partIndex` ordering, the inline-image filter shared
 * with [LibreMailDatabaseTest], REPLACE-on-conflict inserts, and the delete/replace transaction. A
 * parent message row is inserted first because the attachments table foreign-keys to `messages`
 * (Room enables `PRAGMA foreign_keys = ON`).
 */
@RunWith(AndroidJUnit4::class)
class AttachmentDaoTest {

    private lateinit var db: LibreMailDatabase
    private lateinit var dao: AttachmentDao
    private lateinit var messageDao: MessageDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LibreMailDatabase::class.java).build()
        dao = db.attachmentDao()
        messageDao = db.messageDao()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun insertParent(id: String) = messageDao.insertNew(
        listOf(
            MessageEntity(
                id = id,
                accountId = "acct",
                sender = "Ada",
                senderEmail = "ada@example.org",
                subject = "Hi",
                snippet = "",
                body = "",
                timestampMillis = 1_000L,
                isRead = false,
                isStarred = false,
            ),
        ),
    )

    @Test
    fun getForMessageReturnsEveryPartOrderedByPartIndex() = runBlocking {
        insertParent("m1")
        dao.insert(
            listOf(
                AttachmentEntity("m1", 2, "third.pdf", "application/pdf", 30),
                AttachmentEntity("m1", 0, "first.pdf", "application/pdf", 10),
                AttachmentEntity("m1", 1, "second.png", "image/png", 20, contentId = "cid1"),
            ),
        )

        // getForMessage keeps inline (cid) parts and orders by partIndex ascending.
        assertEquals(
            listOf("first.pdf", "second.png", "third.pdf"),
            dao.getForMessage("m1").map { it.filename },
        )
    }

    @Test
    fun insertReplacesAPartWithTheSamePrimaryKey() = runBlocking {
        insertParent("m1")
        dao.insert(listOf(AttachmentEntity("m1", 0, "old.pdf", "application/pdf", 10)))

        // Same (messageId, partIndex) -> REPLACE overwrites the earlier row.
        dao.insert(listOf(AttachmentEntity("m1", 0, "new.pdf", "application/pdf", 99)))

        val parts = dao.getForMessage("m1")
        assertEquals(1, parts.size)
        assertEquals("new.pdf", parts.single().filename)
        assertEquals(99L, parts.single().sizeBytes)
    }

    @Test
    fun deleteForMessageRemovesOnlyThatMessagesParts() = runBlocking {
        insertParent("m1")
        insertParent("m2")
        dao.insert(listOf(AttachmentEntity("m1", 0, "a.pdf", "application/pdf", 1)))
        dao.insert(listOf(AttachmentEntity("m2", 0, "b.pdf", "application/pdf", 1)))

        dao.deleteForMessage("m1")

        assertTrue(dao.getForMessage("m1").isEmpty())
        assertEquals(listOf("b.pdf"), dao.getForMessage("m2").map { it.filename })
    }

    @Test
    fun replaceForMessageSwapsTheWholeAttachmentSetInOneTransaction() = runBlocking {
        insertParent("m1")
        dao.insert(
            listOf(
                AttachmentEntity("m1", 0, "old-a.pdf", "application/pdf", 1),
                AttachmentEntity("m1", 1, "old-b.pdf", "application/pdf", 2),
            ),
        )

        dao.replaceForMessage(
            "m1",
            listOf(AttachmentEntity("m1", 0, "fresh.pdf", "application/pdf", 3)),
        )

        assertEquals(listOf("fresh.pdf"), dao.observeForMessage("m1").first().map { it.filename })
    }
}
