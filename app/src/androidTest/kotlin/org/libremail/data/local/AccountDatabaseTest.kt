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
import org.libremail.data.local.entity.CredentialEntity
import org.libremail.data.local.entity.ServerConfigEmbedded
import org.libremail.data.local.entity.SignatureEntity

/**
 * Behavior of the non-auth [AccountDatabase] that holds accounts, credentials, per-account settings
 * and signatures after they were moved out of the cache database (issue #111). Confirms the tables'
 * foreign keys still cascade from the account, now that they live together in this database.
 */
@RunWith(AndroidJUnit4::class)
class AccountDatabaseTest {

    private lateinit var db: AccountDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AccountDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    private fun account(id: String = "acct") = AccountEntity(
        id = id,
        email = "a@example.org",
        displayName = "A",
        authType = "PASSWORD_IMAP",
        imap = ServerConfigEmbedded("imap.example.org", 993, "SSL_TLS"),
        smtp = ServerConfigEmbedded("smtp.example.org", 465, "SSL_TLS"),
    )

    @Test
    fun credentialRoundTripsAndIsIndependentOfTheAccountRow() = runBlocking<Unit> {
        db.accountDao().upsert(account())
        db.credentialDao().upsert(CredentialEntity("acct", "sealed-secret"))

        assertEquals("sealed-secret", db.credentialDao().getById("acct")?.encryptedSecret)
    }

    @Test
    fun accountSettingsRoundTripAndCascadeWithTheirAccount() = runBlocking<Unit> {
        db.accountDao().upsert(account())
        db.accountSettingsDao().upsert(
            AccountSettingsEntity("acct", signature = "Hi", signatureEnabled = false, notificationsEnabled = false),
        )
        assertEquals("Hi", db.accountSettingsDao().get("acct")?.signature)

        db.accountDao().deleteById("acct")

        assertNull("account_settings must cascade-delete with its account", db.accountSettingsDao().get("acct"))
    }

    @Test
    fun signaturesCascadeWithTheirAccount() = runBlocking<Unit> {
        db.accountDao().upsert(account())
        db.signatureDao().upsert(SignatureEntity("sig-1", "acct", "Work", "<p>Regards</p>", isDefault = true))
        assertEquals(1, db.signatureDao().observeForAccount("acct").first().size)

        db.accountDao().deleteById("acct")

        assertTrue(
            "signatures must cascade-delete with their account",
            db.signatureDao().observeForAccount("acct").first().isEmpty(),
        )
    }
}
