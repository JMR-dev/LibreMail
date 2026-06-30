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
import org.libremail.data.local.entity.AttachmentEntity
import org.libremail.data.local.entity.FolderEntity
import org.libremail.data.local.entity.MessageEntity

/**
 * Schema-behavior tests on the real (v7) Room database. (Migrations from versions before
 * exportSchema was enabled can't be replayed with MigrationTestHelper, since their schema JSONs
 * were never exported; exportSchema is now on so future migrations can be tested.)
 */
@RunWith(AndroidJUnit4::class)
class LibreMailDatabaseTest {

    private lateinit var db: LibreMailDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LibreMailDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    private fun message(id: String, body: String = "") = MessageEntity(
        id = id,
        accountId = "acct",
        sender = "Ada",
        senderEmail = "ada@example.org",
        subject = "Hi",
        snippet = "",
        body = body,
        timestampMillis = 1_000L,
        isRead = false,
        isStarred = false,
    )

    @Test
    fun deletingMessageCascadesToItsAttachments() = runBlocking {
        val messageDao = db.messageDao()
        val attachmentDao = db.attachmentDao()
        messageDao.insertNew(listOf(message("acct:1")))
        attachmentDao.insert(listOf(AttachmentEntity("acct:1", 0, "report.pdf", "application/pdf", 10)))
        assertEquals(1, attachmentDao.observeForMessage("acct:1").first().size)

        messageDao.deleteById("acct:1")

        assertTrue(
            "attachment rows must cascade-delete with their message",
            attachmentDao.observeForMessage("acct:1").first().isEmpty(),
        )
    }

    @Test
    fun searchRowsAreNotInboxAndAreCleared() = runBlocking {
        val messageDao = db.messageDao()
        messageDao.insertNew(listOf(message("acct:1").copy(inInbox = true)))
        messageDao.insertNew(listOf(message("acct:2").copy(inInbox = false)))

        assertEquals(listOf("acct:1"), messageDao.getSyncedIds("acct", "INBOX"))

        messageDao.deleteSearchRows()
        val remaining = messageDao.observeAll().first().map { it.id }
        assertEquals(listOf("acct:1"), remaining)
    }

    @Test
    fun foldersAreStoredOrderedAndReplaceablePerAccount() = runBlocking {
        val folderDao = db.folderDao()
        folderDao.replaceForAccount(
            "acct",
            listOf(
                FolderEntity("acct", "[Gmail]/Sent Mail", "Sent Mail", "SENT", selectable = true, sortOrder = 1),
                FolderEntity("acct", "INBOX", "INBOX", "INBOX", selectable = true, sortOrder = 0),
            ),
        )
        // observeForAccount returns folders ordered by sortOrder.
        assertEquals(
            listOf("INBOX", "[Gmail]/Sent Mail"),
            folderDao.observeForAccount("acct").first().map { it.fullName },
        )

        // replaceForAccount swaps the whole set (delete + insert).
        folderDao.replaceForAccount("acct", listOf(FolderEntity("acct", "Archive", "Archive", "ARCHIVE", true, 0)))
        assertEquals(listOf("Archive"), folderDao.observeForAccount("acct").first().map { it.fullName })
    }

    @Test
    fun syncReconcileIsScopedToASingleFolder() = runBlocking {
        val messageDao = db.messageDao()
        messageDao.insertNew(
            listOf(
                message("acct:INBOX:1").copy(folder = "INBOX"),
                message("acct:INBOX:2").copy(folder = "INBOX"),
                message("acct:Archive:1").copy(folder = "Archive"),
            ),
        )

        // getSyncedIds is scoped to one folder.
        assertEquals(setOf("acct:INBOX:1", "acct:INBOX:2"), messageDao.getSyncedIds("acct", "INBOX").toSet())
        assertEquals(listOf("acct:Archive:1"), messageDao.getSyncedIds("acct", "Archive"))

        // Reconciling the inbox must not touch other folders' rows.
        messageDao.deleteSyncedNotIn("acct", "INBOX", listOf("acct:INBOX:1"))
        assertEquals(
            setOf("acct:INBOX:1", "acct:Archive:1"),
            messageDao.observeAll().first().map { it.id }.toSet(),
        )
    }
}
