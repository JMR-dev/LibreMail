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
import org.libremail.data.local.dao.SignatureDao
import org.libremail.data.local.entity.AccountEntity
import org.libremail.data.local.entity.ServerConfigEmbedded
import org.libremail.data.local.entity.SignatureEntity

/**
 * Real-SQLite behavior of [SignatureDao] in [AccountDatabase]: the default-first / name-ordered
 * observation, the default/first/count reads, and the "exactly one default per account" transaction
 * ([SignatureDao.setDefault]). A parent account row is inserted first because signatures foreign-key
 * to `accounts`.
 */
@RunWith(AndroidJUnit4::class)
class SignatureDaoTest {

    private lateinit var db: AccountDatabase
    private lateinit var dao: SignatureDao
    private lateinit var accountDao: AccountDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AccountDatabase::class.java).build()
        dao = db.signatureDao()
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

    private fun signature(id: String, name: String, isDefault: Boolean = false, accountId: String = "acct") =
        SignatureEntity(
            id = id,
            accountId = accountId,
            name = name,
            contentHtml = "<p>$name</p>",
            isDefault = isDefault,
        )

    @Test
    fun observeForAccountOrdersDefaultFirstThenByNameCaseInsensitive() = runBlocking {
        insertAccount()
        dao.upsert(signature("s-zeta", "Zeta", isDefault = true))
        dao.upsert(signature("s-alpha", "alpha"))
        dao.upsert(signature("s-beta", "Beta"))

        // isDefault DESC puts the default first; the rest sort by name COLLATE NOCASE (alpha < Beta).
        assertEquals(
            listOf("Zeta", "alpha", "Beta"),
            dao.observeForAccount("acct").first().map { it.name },
        )
    }

    @Test
    fun getByIdGetDefaultFirstForAccountAndCountReadTheExpectedRows() = runBlocking {
        insertAccount()
        dao.upsert(signature("s-work", "Work", isDefault = true))
        dao.upsert(signature("s-personal", "aPersonal"))

        assertEquals("Work", dao.getById("s-work")?.name)
        assertNull(dao.getById("absent"))
        assertEquals("Work", dao.getDefault("acct")?.name)
        // firstForAccount ignores isDefault and takes the name-first row (aPersonal < Work).
        assertEquals("aPersonal", dao.firstForAccount("acct")?.name)
        assertEquals(2, dao.countForAccount("acct"))
    }

    @Test
    fun getDefaultIsNullWhenNoSignatureIsMarkedDefault() = runBlocking {
        insertAccount()
        dao.upsert(signature("s-1", "One"))

        assertNull(dao.getDefault("acct"))
        assertEquals(0, dao.countForAccount("absent"))
    }

    @Test
    fun upsertReplacesASignatureAndDeleteRemovesIt() = runBlocking {
        insertAccount()
        dao.upsert(signature("s-1", "Original"))

        dao.upsert(signature("s-1", "Edited"))
        assertEquals("Edited", dao.getById("s-1")?.name)

        dao.delete("s-1")
        assertNull(dao.getById("s-1"))
    }

    @Test
    fun clearDefaultAndMarkDefaultToggleTheFlag() = runBlocking {
        insertAccount()
        dao.upsert(signature("s-1", "One", isDefault = true))

        dao.clearDefault("acct")
        assertNull("clearDefault drops the account's default", dao.getDefault("acct"))

        dao.markDefault("s-1")
        assertEquals("s-1", dao.getDefault("acct")?.id)
    }

    @Test
    fun setDefaultMakesExactlyOneSignatureTheAccountsDefault() = runBlocking {
        insertAccount()
        dao.upsert(signature("s-1", "One", isDefault = true))
        dao.upsert(signature("s-2", "Two"))

        dao.setDefault("acct", "s-2")

        // The transaction clears every other default first, so only s-2 remains default.
        assertEquals("s-2", dao.getDefault("acct")?.id)
        assertEquals(false, dao.getById("s-1")?.isDefault)
        assertEquals(true, dao.getById("s-2")?.isDefault)
    }

    @Test
    fun setDefaultIsScopedToTheAccount() = runBlocking {
        insertAccount("acct")
        insertAccount("acct2")
        dao.upsert(signature("a1", "A1", isDefault = true, accountId = "acct"))
        dao.upsert(signature("b1", "B1", isDefault = true, accountId = "acct2"))

        dao.setDefault("acct", "a1")

        // clearDefault in setDefault only touches the target account; acct2's default is untouched.
        assertEquals("b1", dao.getDefault("acct2")?.id)
    }
}
