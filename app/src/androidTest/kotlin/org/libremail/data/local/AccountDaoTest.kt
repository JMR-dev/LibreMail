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
import org.libremail.data.local.dao.AccountDao
import org.libremail.data.local.entity.AccountEntity
import org.libremail.data.local.entity.ServerConfigEmbedded

/**
 * Real-SQLite behavior of [AccountDao] in the non-auth [AccountDatabase]: the email-ordered
 * list/observe reads, point lookup, upsert-on-conflict, and deletion.
 */
@RunWith(AndroidJUnit4::class)
class AccountDaoTest {

    private lateinit var db: AccountDatabase
    private lateinit var dao: AccountDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AccountDatabase::class.java).build()
        dao = db.accountDao()
    }

    @After
    fun tearDown() = db.close()

    private fun account(id: String, email: String, displayName: String = "Name") = AccountEntity(
        id = id,
        email = email,
        displayName = displayName,
        authType = "PASSWORD_IMAP",
        imap = ServerConfigEmbedded("imap.example.org", 993, "SSL_TLS"),
        smtp = ServerConfigEmbedded("smtp.example.org", 465, "SSL_TLS"),
    )

    @Test
    fun observeAllAndGetAllReturnAccountsOrderedByEmail() = runBlocking {
        dao.upsert(account("2", "zed@example.org"))
        dao.upsert(account("1", "ada@example.org"))

        assertEquals(listOf("ada@example.org", "zed@example.org"), dao.observeAll().first().map { it.email })
        assertEquals(listOf("ada@example.org", "zed@example.org"), dao.getAll().map { it.email })
    }

    @Test
    fun getByIdReturnsTheAccountOrNullAndEmbedsServerConfig() = runBlocking {
        dao.upsert(account("acct", "ada@example.org"))

        val stored = dao.getById("acct")
        assertEquals("ada@example.org", stored?.email)
        assertEquals(993, stored?.imap?.port)
        assertEquals("smtp.example.org", stored?.smtp?.host)
        assertNull(dao.getById("absent"))
    }

    @Test
    fun upsertOnAConflictingIdUpdatesTheRowInPlace() = runBlocking {
        // Non-destructive by design (issue #309): a second upsert of the same id refreshes the row
        // rather than delete-then-reinserting it (which would cascade-delete settings/signatures).
        dao.upsert(account("acct", "ada@example.org", displayName = "Ada"))

        dao.upsert(account("acct", "ada@example.org", displayName = "Ada Lovelace"))

        assertEquals("Ada Lovelace", dao.getById("acct")?.displayName)
        assertEquals(1, dao.getAll().size)
    }

    @Test
    fun deleteByIdRemovesTheAccount() = runBlocking {
        dao.upsert(account("acct", "ada@example.org"))

        dao.deleteById("acct")

        assertNull(dao.getById("acct"))
    }
}
