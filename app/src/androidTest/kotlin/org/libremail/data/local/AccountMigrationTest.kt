// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Replays [AccountDatabase]'s migrations against the schema JSONs exported to `app/schemas` (shipped
 * to the test APK as assets). AccountDatabase is the non-auth account store split out of the cache in
 * issue #111; like the cache database it registers no destructive fallback, so a drifted migration
 * would crash upgrading users at first open — this test makes such drift fail in CI instead. Kept
 * separate from [MigrationTest] because each database has its own version line and MigrationTestHelper
 * binds to a single [androidx.room.RoomDatabase] class.
 */
@RunWith(AndroidJUnit4::class)
class AccountMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AccountDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    /**
     * v1 -> v2 (issue #164): `accounts.sortOrder` appears, and existing accounts are backfilled with a
     * stable initial order matching the previous alphabetical (`ORDER BY email`) listing. The rows are
     * inserted out of alphabetical order to prove the rank is by email, not insertion order.
     */
    @Test
    fun migrate1To2_addsSortOrderBackfilledByEmailRank() {
        helper.createDatabase(TEST_DB, 1).apply {
            insertAccount("c", "carol@example.org")
            insertAccount("a", "ada@example.org")
            insertAccount("b", "bob@example.org")
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, ACCOUNT_MIGRATION_1_2)

        db.query("SELECT id, sortOrder FROM accounts ORDER BY sortOrder").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("ada ranks first alphabetically, so sortOrder 0", "a", c.getString(0))
            assertEquals(0, c.getInt(1))
            assertTrue(c.moveToNext())
            assertEquals("b", c.getString(0))
            assertEquals(1, c.getInt(1))
            assertTrue(c.moveToNext())
            assertEquals("c", c.getString(0))
            assertEquals(2, c.getInt(1))
            assertFalse("all three pre-upgrade accounts must survive, and nothing else", c.moveToNext())
        }
        db.close()
    }

    private fun SupportSQLiteDatabase.insertAccount(id: String, email: String) {
        execSQL(
            "INSERT INTO accounts (id, email, displayName, authType, imap_host, imap_port, imap_security, " +
                "smtp_host, smtp_port, smtp_security) VALUES ('$id', '$email', '$id', 'PASSWORD_IMAP', " +
                "'imap.example.org', 993, 'SSL_TLS', 'smtp.example.org', 465, 'SSL_TLS')",
        )
    }

    private companion object {
        const val TEST_DB = "account-migration-test.db"
    }
}
