// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Validates the v12 -> v13 migration (issues #12/#13): `messages.uid` is added and backfilled from the
 * id's numeric tail, `account_settings` gains the nullable retention overrides, and the
 * `backfill_progress` table is created. `runMigrationsAndValidate` additionally checks the whole
 * migrated schema matches the exported v13 schema.
 */
@RunWith(AndroidJUnit4::class)
class Migration12To13Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LibreMailDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate12To13_backfillsUidAndAddsRetentionAndBackfillTables() {
        helper.createDatabase(DB_NAME, 12).apply {
            // A plain inbox row, a folder name containing special characters, and a folder name that
            // ends in a digit — all must recover the trailing UID correctly.
            insertV12Message("acct:INBOX:42")
            insertV12Message("acct:[Gmail]/Sent Mail:7")
            insertV12Message("acct:Folder2:15")
            close()
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 13, true, MIGRATION_12_13)

        // uid is materialized from the id's numeric tail.
        assertEquals(42L, uidOf(db, "acct:INBOX:42"))
        assertEquals(7L, uidOf(db, "acct:[Gmail]/Sent Mail:7"))
        assertEquals(15L, uidOf(db, "acct:Folder2:15"))

        // The new retention columns exist and default to NULL (= inherit the global default).
        db.query("SELECT retentionCount, retentionMonths FROM account_settings").use { c ->
            // No rows required; the query succeeding proves the columns exist.
            assertTrue(c.columnCount == 2)
        }

        // The backfill_progress table exists and accepts a row.
        db.execSQL(
            "INSERT INTO backfill_progress (accountId, folder, nextBeforeUid, complete) " +
                "VALUES ('acct', 'INBOX', 41, 0)",
        )
        db.query("SELECT nextBeforeUid FROM backfill_progress WHERE accountId='acct' AND folder='INBOX'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(41L, c.getLong(0))
        }
        db.close()
    }

    private fun uidOf(db: androidx.sqlite.db.SupportSQLiteDatabase, id: String): Long =
        db.query("SELECT uid FROM messages WHERE id = ?", arrayOf<Any>(id)).use { c ->
            assertTrue("row $id must exist", c.moveToFirst())
            c.getLong(0)
        }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.insertV12Message(id: String) {
        execSQL(
            "INSERT INTO messages (id, accountId, sender, senderEmail, subject, snippet, body, isHtml, " +
                "timestampMillis, isRead, isStarred, folder, inInbox, bodyFetched) " +
                "VALUES (?, 'acct', 'Ada', 'ada@example.org', 'Hi', '', '', 0, 1000, 0, 0, 'INBOX', 1, 0)",
            arrayOf<Any>(id),
        )
    }

    private companion object {
        const val DB_NAME = "migration-12-13-test.db"
    }
}
