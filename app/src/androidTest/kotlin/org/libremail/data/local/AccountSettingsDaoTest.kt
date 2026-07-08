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
import org.libremail.data.local.dao.AccountSettingsDao
import org.libremail.data.local.entity.AccountEntity
import org.libremail.data.local.entity.AccountSettingsEntity
import org.libremail.data.local.entity.ServerConfigEmbedded

/**
 * Real-SQLite behavior of [AccountSettingsDao] in [AccountDatabase]: the live observer (which emits
 * null before a row exists), the one-shot read, upsert-on-conflict, and the nullable retention
 * overrides round-tripping. A parent account row is inserted first because the settings table
 * foreign-keys to `accounts`.
 */
@RunWith(AndroidJUnit4::class)
class AccountSettingsDaoTest {

    private lateinit var db: AccountDatabase
    private lateinit var dao: AccountSettingsDao
    private lateinit var accountDao: AccountDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AccountDatabase::class.java).build()
        dao = db.accountSettingsDao()
        accountDao = db.accountDao()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun insertAccount(id: String = "acct") = accountDao.upsert(
        AccountEntity(
            id = id,
            email = "$id@example.org",
            displayName = "Name",
            authType = "PASSWORD_IMAP",
            imap = ServerConfigEmbedded("imap.example.org", 993, "SSL_TLS"),
            smtp = ServerConfigEmbedded("smtp.example.org", 465, "SSL_TLS"),
        ),
    )

    @Test
    fun observeEmitsNullBeforeARowExistsThenTheRow() = runBlocking {
        insertAccount()
        assertNull("no settings row yet -> the observer emits null", dao.observe("acct").first())

        dao.upsert(AccountSettingsEntity("acct", signature = "Cheers"))

        assertEquals("Cheers", dao.observe("acct").first()?.signature)
    }

    @Test
    fun getReturnsTheStoredRowOrNull() = runBlocking {
        insertAccount()
        dao.upsert(AccountSettingsEntity("acct", signatureEnabled = false, notificationsEnabled = false))

        val stored = dao.get("acct")
        assertEquals(false, stored?.signatureEnabled)
        assertEquals(false, stored?.notificationsEnabled)
        assertNull(dao.get("absent"))
    }

    @Test
    fun upsertReplacesTheSettingsAndRoundTripsNullableRetentionOverrides() = runBlocking {
        insertAccount()
        dao.upsert(AccountSettingsEntity("acct", retentionCount = null, retentionMonths = null))
        assertNull(dao.get("acct")?.retentionCount)
        assertNull(dao.get("acct")?.retentionMonths)

        dao.upsert(AccountSettingsEntity("acct", retentionCount = 500, retentionMonths = 6))

        val stored = dao.get("acct")
        assertEquals(500, stored?.retentionCount)
        assertEquals(6, stored?.retentionMonths)
    }

    @Test
    fun readModifyWriteAppliesTheTransformToTheStoredRow() = runBlocking {
        insertAccount()
        dao.upsert(AccountSettingsEntity("acct", signature = "old", notificationsEnabled = false))

        // The read + transform + write run in one transaction (issue #313); the transform gets the stored
        // row and changes one field, so the un-touched fields are carried forward.
        dao.readModifyWrite("acct") { stored -> stored!!.copy(signature = "new") }

        val result = dao.get("acct")
        assertEquals("new", result?.signature)
        assertEquals(false, result?.notificationsEnabled)
    }

    @Test
    fun readModifyWriteTransformsANullRowForAnUnconfiguredAccount() = runBlocking {
        insertAccount()
        // No settings row yet: the transform receives null and builds the first row.
        dao.readModifyWrite("acct") { stored ->
            stored?.copy(signature = "x") ?: AccountSettingsEntity("acct", signature = "seeded")
        }

        assertEquals("seeded", dao.get("acct")?.signature)
    }
}
