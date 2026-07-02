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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.data.local.entity.AccountEntity
import org.libremail.data.local.entity.AccountSettingsEntity
import org.libremail.data.local.entity.AttachmentEntity
import org.libremail.data.local.entity.FolderEntity
import org.libremail.data.local.entity.MessageEntity
import org.libremail.data.local.entity.ServerConfigEmbedded

/**
 * Schema-behavior tests on a fresh in-memory database at the current version. The migration DDL
 * itself is exercised by [MigrationTest], which replays the schema chain exported to app/schemas.
 * (Migrations from before v7 predate schema export, so they can't be replayed there.)
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
    fun observeUnreadCountsAggregatesUnreadSyncedRowsPerAccountAndFolder() = runBlocking {
        val messageDao = db.messageDao()
        messageDao.insertNew(
            listOf(
                // acct / INBOX: two unread + one read -> counts 2.
                message("acct:INBOX:1").copy(folder = "INBOX", isRead = false),
                message("acct:INBOX:2").copy(folder = "INBOX", isRead = false),
                message("acct:INBOX:3").copy(folder = "INBOX", isRead = true),
                // acct / Archive: one unread -> counts 1.
                message("acct:Archive:1").copy(folder = "Archive", isRead = false),
                // An unread server-search hit (inInbox = false) must never inflate a badge.
                message("acct:INBOX:search").copy(folder = "INBOX", isRead = false, inInbox = false),
                // A second account's unread inbox row is counted under its own accountId.
                message("acct2:INBOX:1").copy(accountId = "acct2", folder = "INBOX", isRead = false),
            ),
        )

        val counts = messageDao.observeUnreadCounts().first()
            .associate { (it.accountId to it.folder) to it.unreadCount }

        assertEquals(2, counts[("acct" to "INBOX")])
        assertEquals(1, counts[("acct" to "Archive")])
        assertEquals(1, counts[("acct2" to "INBOX")])
        // Fully-read folders and search-only rows produce no group at all.
        assertEquals(3, counts.size)
    }

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
    fun accountSettingsRoundTripAndCascadeWithTheirAccount() = runBlocking {
        val accountDao = db.accountDao()
        val settingsDao = db.accountSettingsDao()
        accountDao.upsert(
            AccountEntity(
                id = "acct",
                email = "a@example.org",
                displayName = "A",
                authType = "PASSWORD_IMAP",
                imap = ServerConfigEmbedded("imap.example.org", 993, "SSL_TLS"),
                smtp = ServerConfigEmbedded("smtp.example.org", 465, "SSL_TLS"),
            ),
        )
        settingsDao.upsert(
            AccountSettingsEntity("acct", signature = "Hi", signatureEnabled = false, notificationsEnabled = false),
        )
        assertEquals("Hi", settingsDao.get("acct")?.signature)

        accountDao.deleteById("acct")

        assertNull("account_settings must cascade-delete with its account", settingsDao.get("acct"))
    }

    @Test
    fun searchRowsAreNotInboxAndAreCleared() = runBlocking {
        val messageDao = db.messageDao()
        messageDao.insertNew(listOf(message("acct:1").copy(inInbox = true)))
        messageDao.insertNew(listOf(message("acct:2").copy(inInbox = false)))

        assertEquals(listOf("acct:1"), messageDao.getSyncedIds("acct", "INBOX"))

        messageDao.deleteSearchRows()
        val remaining = messageDao.observeSummaries().first().map { it.id }
        assertEquals(listOf("acct:1"), remaining)
    }

    @Test
    fun observeSummariesReadsRowsWhoseBodiesExceedTheCursorWindow() = runBlocking {
        val messageDao = db.messageDao()
        // Each body is larger than SQLite's shared (~2 MB) CursorWindow. The old list query did
        // SELECT * and dragged these bodies through the window, overflowing it with
        // "Couldn't read row … from CursorWindow" (issue #51). observeSummaries omits body, so the
        // rows stay tiny and read fine.
        val hugeBody = "x".repeat(3 * 1024 * 1024)
        messageDao.insertNew(
            listOf(
                message("acct:1", body = hugeBody),
                message("acct:2", body = hugeBody),
            ),
        )

        val ids = messageDao.observeSummaries().first().map { it.id }.toSet()

        assertEquals(setOf("acct:1", "acct:2"), ids)
    }

    @Test
    fun foldersAreStoredOrderedAndReplaceablePerAccount() = runBlocking {
        val folderDao = db.folderDao()
        folderDao.replaceForAccount(
            "acct",
            listOf(
                FolderEntity(
                    accountId = "acct",
                    fullName = "[Gmail]/Sent Mail",
                    displayName = "Sent Mail",
                    role = "SENT",
                    selectable = true,
                    sortOrder = 1,
                    specialUse = true,
                ),
                FolderEntity("acct", "INBOX", "INBOX", "INBOX", selectable = true, sortOrder = 0),
            ),
        )
        // observeForAccount returns folders ordered by sortOrder, with specialUse round-tripped.
        val stored = folderDao.observeForAccount("acct").first()
        assertEquals(listOf("INBOX", "[Gmail]/Sent Mail"), stored.map { it.fullName })
        assertEquals(listOf(false, true), stored.map { it.specialUse })

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

        // Reconciling the inbox must not touch other folders' rows (windowed reconcile; whole-inbox
        // window since these rows have uid 0).
        messageDao.deleteSyncedInWindowNotIn("acct", "INBOX", minWindowUid = 0, keepIds = listOf("acct:INBOX:1"))
        assertEquals(
            setOf("acct:INBOX:1", "acct:Archive:1"),
            messageDao.observeSummaries().first().map { it.id }.toSet(),
        )
    }
}
