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

    private fun account(id: String = "acct", email: String = "$id@example.org") = AccountEntity(
        id = id,
        email = email,
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
    fun insertAtEndAppendsAccountsInIncreasingSortOrder() = runBlocking<Unit> {
        db.accountDao().insertAtEnd(account("a"))
        db.accountDao().insertAtEnd(account("b"))
        db.accountDao().insertAtEnd(account("c"))

        // Each new account lands at the end: current max + 1, starting from 0 (issue #164).
        assertEquals(0, db.accountDao().getById("a")?.sortOrder)
        assertEquals(1, db.accountDao().getById("b")?.sortOrder)
        assertEquals(2, db.accountDao().getById("c")?.sortOrder)
        // observeAll now orders by sortOrder, i.e. the append (insertion) order.
        assertEquals(listOf("a", "b", "c"), db.accountDao().observeAll().first().map { it.id })
    }

    @Test
    fun reorderPersistsAndBothQueriesReflectTheNewOrder() = runBlocking<Unit> {
        listOf("a", "b", "c").forEach { db.accountDao().insertAtEnd(account(it)) }

        db.accountDao().reorder(listOf("c", "a", "b"))

        assertEquals(listOf("c", "a", "b"), db.accountDao().observeAll().first().map { it.id })
        assertEquals(listOf("c", "a", "b"), db.accountDao().getAll().map { it.id })
        // sortOrder is renumbered to the new positions, so the order survives an app restart.
        assertEquals(0, db.accountDao().getById("c")?.sortOrder)
        assertEquals(1, db.accountDao().getById("a")?.sortOrder)
        assertEquals(2, db.accountDao().getById("b")?.sortOrder)
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

    @Test
    fun upsertOnAnExistingIdUpdatesInPlaceWithoutCascadingSettingsOrSignatures() = runBlocking<Unit> {
        // Regression for issue #309: an @Insert(REPLACE) upsert deletes-then-reinserts on an id
        // conflict, firing the ON DELETE CASCADE that wipes the account's settings + signatures.
        db.accountDao().upsert(account("acct"))
        db.accountSettingsDao().upsert(AccountSettingsEntity("acct", signature = "Keep me"))
        db.signatureDao().upsert(SignatureEntity("sig-1", "acct", "Work", "<p>Regards</p>", isDefault = true))

        db.accountDao().upsert(account("acct").copy(displayName = "Updated"))

        assertEquals("Updated", db.accountDao().getById("acct")?.displayName)
        assertEquals("Keep me", db.accountSettingsDao().get("acct")?.signature)
        assertEquals(1, db.signatureDao().observeForAccount("acct").first().size)
        assertEquals(1, db.accountDao().getAll().size)
    }

    @Test
    fun insertAtEndReAddingAnExistingAccountKeepsItsSettingsSignaturesAndPosition() = runBlocking<Unit> {
        // The production re-add path (addOutlookAccount -> insertAtEnd -> upsert). Re-adding a
        // deterministic id must not cascade-delete its settings/signatures nor move it to the end.
        db.accountDao().insertAtEnd(account("a"))
        db.accountDao().insertAtEnd(account("b"))
        db.accountSettingsDao().upsert(AccountSettingsEntity("b", signature = "Sig B", signatureEnabled = false))
        db.signatureDao().upsert(SignatureEntity("sig-b", "b", "Work", "<p>Regards</p>", isDefault = true))

        db.accountDao().insertAtEnd(account("b").copy(displayName = "Renamed"))

        // Updated in place, position preserved (still 1, not appended after), children intact.
        assertEquals("Renamed", db.accountDao().getById("b")?.displayName)
        assertEquals(1, db.accountDao().getById("b")?.sortOrder)
        assertEquals("Sig B", db.accountSettingsDao().get("b")?.signature)
        assertEquals(false, db.accountSettingsDao().get("b")?.signatureEnabled)
        assertEquals(1, db.signatureDao().observeForAccount("b").first().size)
        assertEquals(2, db.accountDao().getAll().size)
    }
}
