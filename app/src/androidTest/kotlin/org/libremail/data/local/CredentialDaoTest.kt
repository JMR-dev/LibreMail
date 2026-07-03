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
import org.libremail.data.local.dao.CredentialDao
import org.libremail.data.local.entity.CredentialEntity

/**
 * Real-SQLite behavior of [CredentialDao] in [AccountDatabase]: the point read, upsert-on-conflict
 * (a rotated secret overwrites the old one), and deletion. The credentials table is not foreign-keyed
 * to `accounts`, so these rows stand alone.
 */
@RunWith(AndroidJUnit4::class)
class CredentialDaoTest {

    private lateinit var db: AccountDatabase
    private lateinit var dao: CredentialDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AccountDatabase::class.java).build()
        dao = db.credentialDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun getByIdReturnsTheSecretOrNull() = runBlocking {
        dao.upsert(CredentialEntity("acct", "sealed-secret"))

        assertEquals("sealed-secret", dao.getById("acct")?.encryptedSecret)
        assertNull(dao.getById("absent"))
    }

    @Test
    fun upsertReplacesTheSecretForTheSameAccount() = runBlocking {
        dao.upsert(CredentialEntity("acct", "old"))

        dao.upsert(CredentialEntity("acct", "rotated"))

        assertEquals("rotated", dao.getById("acct")?.encryptedSecret)
    }

    @Test
    fun deleteByIdRemovesTheCredential() = runBlocking {
        dao.upsert(CredentialEntity("acct", "sealed-secret"))

        dao.deleteById("acct")

        assertNull(dao.getById("acct"))
    }
}
