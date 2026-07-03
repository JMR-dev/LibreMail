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
import org.libremail.data.local.dao.MessageDao
import org.libremail.data.local.entity.MessageEntity

/**
 * Real-SQLite behavior of the body-less routing projection [MessageDao.getRouting] /
 * [MessageDao.getRoutingByIds] (issue #186). The open path and the flag/move callers route on these
 * instead of pulling the whole `body` blob through [MessageDao.getById]. These tests pin that the
 * projection maps every routing/flag column correctly (and can do so for a row whose body is large,
 * which is exactly the over-fetch the projection avoids).
 */
@RunWith(AndroidJUnit4::class)
class MessageDaoRoutingTest {

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
        uid: Long = 0L,
        isRead: Boolean = false,
        isStarred: Boolean = false,
        bodyFetched: Boolean = false,
        isHtml: Boolean = false,
        body: String = "",
    ) = MessageEntity(
        id = id,
        accountId = accountId,
        sender = "Ada",
        senderEmail = "ada@example.org",
        subject = "Hi",
        snippet = "",
        body = body,
        isHtml = isHtml,
        timestampMillis = 1_000L,
        isRead = isRead,
        isStarred = isStarred,
        folder = folder,
        bodyFetched = bodyFetched,
        uid = uid,
    )

    @Test
    fun getRoutingProjectsEveryRoutingAndFlagColumn() = runBlocking {
        dao.insertNew(
            listOf(
                message(
                    id = "acct:Archive:9",
                    folder = "Archive",
                    uid = 9,
                    isRead = true,
                    isStarred = true,
                    bodyFetched = true,
                    isHtml = true,
                    body = "x".repeat(4_000), // a large body the projection must not need to read
                ),
            ),
        )

        val routing = requireNotNull(dao.getRouting("acct:Archive:9"))

        assertEquals("acct:Archive:9", routing.id)
        assertEquals("acct", routing.accountId)
        assertEquals("Archive", routing.folder)
        assertEquals(9L, routing.uid)
        assertEquals(true, routing.isRead)
        assertEquals(true, routing.isStarred)
        assertEquals(true, routing.bodyFetched)
        assertEquals(true, routing.isHtml)
    }

    @Test
    fun getRoutingIsNullForAnUnknownId() = runBlocking {
        assertNull(dao.getRouting("acct:INBOX:404"))
    }

    @Test
    fun getRoutingByIdsReturnsOnlyTheRequestedRows() = runBlocking {
        dao.insertNew(
            listOf(
                message(id = "acct:INBOX:1", folder = "INBOX", uid = 1),
                message(id = "acct:INBOX:2", folder = "INBOX", uid = 2),
                message(id = "acct2:Sent:3", folder = "Sent", accountId = "acct2", uid = 3),
            ),
        )

        val routings = dao.getRoutingByIds(listOf("acct:INBOX:1", "acct2:Sent:3"))

        assertEquals(setOf("acct:INBOX:1", "acct2:Sent:3"), routings.map { it.id }.toSet())
        assertEquals(setOf("INBOX", "Sent"), routings.map { it.folder }.toSet())
    }
}
