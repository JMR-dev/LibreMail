// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local

import androidx.room.migration.Migration
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
import java.lang.reflect.Modifier

/**
 * Replays the app's Room migrations against the schema JSONs exported to `app/schemas` (shipped to
 * the test APK as assets). DatabaseModule deliberately registers no destructive fallback — dropping
 * tables would lose stored accounts and credentials — so a drifted migration crashes upgrading
 * users at first database open; these tests make such drift fail in CI instead (issue #63).
 *
 * The migration list is discovered from Migrations.kt and the target version from the newest
 * exported schema, so a future migration is covered automatically: author the `Migration`, register
 * it in DatabaseModule, commit the new schema JSON, and [migratingFromV7ReplaysEveryMigrationAndPreservesData]
 * replays it with no edit here. (Only v7+ can be replayed — older versions predate schema export.)
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LibreMailDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    /** v11 -> v12 (PR #54): `folders.specialUse` appears defaulting to 0 and existing data survives. */
    @Test
    fun migrate11To12_defaultsExistingFoldersToNotSpecialUse() {
        helper.createDatabase(TEST_DB, 11).apply {
            insertAccount()
            execSQL(
                "INSERT INTO folders (accountId, fullName, displayName, role, selectable, sortOrder) VALUES " +
                    "('acct', 'INBOX', 'INBOX', 'INBOX', 1, 0), " +
                    "('acct', '[Gmail]/Sent Mail', 'Sent Mail', 'SENT', 1, 1)",
            )
            execSQL(
                "INSERT INTO messages (id, accountId, sender, senderEmail, subject, snippet, body, isHtml, " +
                    "timestampMillis, isRead, isStarred, folder, inInbox, bodyFetched) " +
                    "VALUES ('acct:INBOX:1', 'acct', 'Ada', 'ada@example.org', 'Hi', '', '', 0, 1000, 0, 0, " +
                    "'INBOX', 1, 0)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 12, true, MIGRATION_11_12)

        db.query("SELECT fullName, specialUse FROM folders ORDER BY sortOrder").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("INBOX", c.getString(0))
            assertEquals(0, c.getInt(1))
            assertTrue(c.moveToNext())
            assertEquals("[Gmail]/Sent Mail", c.getString(0))
            assertEquals(0, c.getInt(1))
            assertFalse("both pre-upgrade folder rows must survive, and nothing else", c.moveToNext())
        }
        // The folders' account and cached mail are untouched.
        assertEquals(1, db.count("accounts"))
        assertEquals(1, db.count("messages"))
        db.close()
    }

    /** v14 -> v15 (issue #66): `folders.hierarchyDelimiter` appears defaulting to NULL; data survives. */
    @Test
    fun migrate14To15_addsNullHierarchyDelimiterToFolders() {
        helper.createDatabase(TEST_DB, 14).apply {
            insertAccount()
            execSQL(
                "INSERT INTO folders (accountId, fullName, displayName, role, selectable, sortOrder, specialUse) " +
                    "VALUES ('acct', 'INBOX', 'INBOX', 'INBOX', 1, 0, 0), " +
                    "('acct', 'Work.Reports', 'Reports', 'NORMAL', 1, 1, 0)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 15, true, MIGRATION_14_15)

        db.query("SELECT fullName, hierarchyDelimiter FROM folders ORDER BY sortOrder").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("INBOX", c.getString(0))
            assertTrue("existing folders read a null delimiter until the next refresh", c.isNull(1))
            assertTrue(c.moveToNext())
            assertEquals("Work.Reports", c.getString(0))
            assertTrue(c.isNull(1))
            assertFalse("both pre-upgrade folder rows must survive, and nothing else", c.moveToNext())
        }
        // The folders' account is untouched.
        assertEquals(1, db.count("accounts"))
        db.close()
    }

    /**
     * A gap in the chain (or a migration whose target schema was never committed) crashes upgrading
     * users, so fail fast with a readable message before replaying anything.
     */
    @Test
    fun migrationsFormOneUnbrokenChainUpToTheLatestExportedSchema() {
        assertEquals(
            "Migrations.kt must chain every version step up to the newest exported schema " +
                "(DatabaseModule registers no destructive fallback, so a gap crashes upgrades)",
            (1 until latestExportedSchemaVersion()).map { it to it + 1 },
            allAppMigrations.map { it.startVersion to it.endVersion },
        )
    }

    /**
     * Creates a database at v7 (the oldest exported schema), fills it like a used install, then
     * replays every migration one step at a time — `runMigrationsAndValidate` diffs the migrated
     * schema against each version's exported JSON, so a failure names the exact step that drifted.
     */
    @Test
    fun migratingFromV7ReplaysEveryMigrationAndPreservesData() {
        helper.createDatabase(TEST_DB, OLDEST_EXPORTED_SCHEMA).apply {
            seedVersion7Cache()
            close()
        }

        var open: SupportSQLiteDatabase? = null
        for (migration in allAppMigrations.filter { it.startVersion >= OLDEST_EXPORTED_SCHEMA }) {
            open?.close()
            val stepDb = helper.runMigrationsAndValidate(TEST_DB, migration.endVersion, true, migration)
            stepDb.writeMidChainData()
            // v16 moves the account tables out to AccountDatabase and drops them, so assert their rows
            // and backfills reached v15 intact — just before the move (issue #111).
            if (stepDb.version == 15) stepDb.assertAccountDataPresentAtV15()
            open = stepDb
        }
        val db = checkNotNull(open) { "no migration starts at v$OLDEST_EXPORTED_SCHEMA" }

        assertEquals("the chain must end at the newest exported schema", latestExportedSchemaVersion(), db.version)
        db.assertVersion7CacheSurvived()
        db.assertMigrationBackfillsApplied()
        db.assertAccountTablesDroppedAtV16()
        db.close()
    }

    /** v15 -> v16 (issue #111): the moved account tables are dropped and the mail cache is untouched. */
    @Test
    fun migrate15To16_dropsMovedAccountTablesAndKeepsCache() {
        helper.createDatabase(TEST_DB, 15).apply {
            insertAccount()
            execSQL("INSERT INTO credentials (accountId, encryptedSecret) VALUES ('acct', 'sealed')")
            execSQL(
                "INSERT INTO messages (id, accountId, sender, senderEmail, subject, snippet, body, isHtml, " +
                    "timestampMillis, isRead, isStarred, folder, inInbox, bodyFetched, uid) VALUES " +
                    "('acct:INBOX:1', 'acct', 'Ada', 'ada@example.org', 'Hi', '', '', 0, 1000, 0, 0, " +
                    "'INBOX', 1, 0, 1)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 16, true, MIGRATION_15_16)

        db.assertAccountTablesDroppedAtV16()
        assertEquals("the mail cache must be untouched by 15->16", 1, db.count("messages"))
        db.close()
    }

    /** The newest schema JSON exported to app/schemas (shipped to the test APK as assets). */
    private fun latestExportedSchemaVersion(): Int {
        val schemaFolder = checkNotNull(LibreMailDatabase::class.java.canonicalName)
        val versions = InstrumentationRegistry.getInstrumentation().context.assets.list(schemaFolder)
            .orEmpty()
            .mapNotNull { it.removeSuffix(".json").toIntOrNull() }
        check(versions.isNotEmpty()) { "no exported schemas under androidTest assets/$schemaFolder" }
        return versions.max()
    }

    /** The accounts table has kept this shape since before v7, so every test can share one insert. */
    private fun SupportSQLiteDatabase.insertAccount() {
        execSQL(
            "INSERT INTO accounts (id, email, displayName, authType, imap_host, imap_port, imap_security, " +
                "smtp_host, smtp_port, smtp_security) VALUES ('acct', 'ada@example.org', 'Ada', " +
                "'PASSWORD_IMAP', 'imap.example.org', 993, 'SSL_TLS', 'smtp.example.org', 465, 'SSL_TLS')",
        )
    }

    /** Fills a v7 database the way a used install would look (columns exactly as in 7.json). */
    private fun SupportSQLiteDatabase.seedVersion7Cache() {
        insertAccount()
        execSQL("INSERT INTO credentials (accountId, encryptedSecret) VALUES ('acct', 'sealed-secret')")
        execSQL(
            "INSERT INTO messages (id, accountId, sender, senderEmail, subject, snippet, body, isHtml, " +
                "timestampMillis, isRead, isStarred, inInbox, bodyFetched) VALUES " +
                "('acct:1', 'acct', 'Ada', 'ada@example.org', 'Analytical engines', 'Dear Charles', " +
                "'Dear Charles, the mill works.', 0, 1000, 1, 0, 1, 1), " +
                "('acct:2', 'acct', 'Charles', 'charles@example.org', 'Re: engines', '', '', 0, 2000, 0, 1, 1, 0)",
        )
        execSQL(
            "INSERT INTO attachments (messageId, partIndex, filename, mimeType, sizeBytes) " +
                "VALUES ('acct:1', 0, 'notes.pdf', 'application/pdf', 2048)",
        )
        execSQL(
            "INSERT INTO outbox (id, accountId, toAddresses, ccAddresses, subject, body, createdAt, " +
                "lastError) VALUES ('out-1', 'acct', 'charles@example.org', '', 'Queued', 'Body', 3000, NULL)",
        )
        execSQL(
            "INSERT INTO drafts (id, accountId, toAddresses, ccAddresses, subject, body, updatedAt, " +
                "attachments) VALUES ('draft-1', 'acct', 'charles@example.org', '', 'Draft', 'Text', 4000, '')",
        )
    }

    /** Rows a user would write at intermediate versions; they must survive the rest of the chain. */
    private fun SupportSQLiteDatabase.writeMidChainData() {
        when (version) {
            // v8 is the first version with a folders table; 11->12 must stamp this row specialUse = 0.
            8 -> execSQL(
                "INSERT INTO folders (accountId, fullName, displayName, role, selectable, sortOrder) " +
                    "VALUES ('acct', 'INBOX', 'INBOX', 'INBOX', 1, 0)",
            )
            // A signature saved by a v9 user: 10->11 must carry it into the new signatures table.
            9 -> execSQL(
                "UPDATE account_settings SET signature = 'Cheers,' || char(10) || 'Ada' " +
                    "WHERE accountId = 'acct'",
            )
        }
    }

    /** Every mail-cache row cached at v7 must survive to v16 (account tables are checked separately). */
    private fun SupportSQLiteDatabase.assertVersion7CacheSurvived() {
        assertEquals(2, count("messages"))
        assertEquals(1, count("outbox"))
        assertEquals(1, count("drafts"))
        query("SELECT folder, subject, isRead FROM messages WHERE id = 'acct:1'").use { c ->
            assertTrue("message cached at v7 must survive", c.moveToFirst())
            assertEquals("7->8 files pre-upgrade messages under INBOX", "INBOX", c.getString(0))
            assertEquals("Analytical engines", c.getString(1))
            assertEquals(1, c.getInt(2))
        }
        query("SELECT filename FROM attachments WHERE messageId = 'acct:1'").use { c ->
            assertTrue("attachment rows must survive the 6->7 style table rebuilds", c.moveToFirst())
            assertEquals("notes.pdf", c.getString(0))
        }
    }

    /** Cache-table columns/rows the migrations backfill must hold their documented defaults at v16. */
    private fun SupportSQLiteDatabase.assertMigrationBackfillsApplied() {
        // 9->10 adds bcc columns defaulting to ''; 10->11 adds nullable bodyHtml.
        query("SELECT bccAddresses, bodyHtml FROM outbox WHERE id = 'out-1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("", c.getString(0))
            assertTrue("bodyHtml must default to null (plaintext-only)", c.isNull(1))
        }
        query("SELECT bccAddresses, bodyHtml FROM drafts WHERE id = 'draft-1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("", c.getString(0))
            assertTrue(c.isNull(1))
        }
        // 11->12 stamps the folder cached at v8 as not special-use.
        query("SELECT specialUse FROM folders WHERE fullName = 'INBOX'").use { c ->
            assertTrue("folder cached at v8 must survive to the newest version", c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        // 14->15 adds a nullable hierarchyDelimiter; the folder cached at v8 predates it, so it reads
        // null and the drawer infers the separator from the name until the next folder refresh.
        query("SELECT hierarchyDelimiter FROM folders WHERE fullName = 'INBOX'").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue("a folder cached before v15 must read a null delimiter", c.isNull(0))
        }
    }

    /**
     * The account tables' rows + migration backfills must be intact at v15, just before 15->16 moves
     * them to [AccountDatabase] and drops them (issue #111). AccountDataMigrator's own copy is
     * exercised in `AccountDataMigratorTest`; here we only assert the source rows reach the move point.
     */
    private fun SupportSQLiteDatabase.assertAccountDataPresentAtV15() {
        assertEquals(1, count("accounts"))
        query("SELECT encryptedSecret FROM credentials WHERE accountId = 'acct'").use { c ->
            assertTrue("stored credentials must reach v15 before the move", c.moveToFirst())
            assertEquals("sealed-secret", c.getString(0))
        }
        // 8->9 backfills one default settings row per existing account.
        query("SELECT signatureEnabled, notificationsEnabled FROM account_settings").use { c ->
            assertTrue("8->9 must backfill a settings row for the v7 account", c.moveToFirst())
            assertEquals(1, c.getInt(0))
            assertEquals(1, c.getInt(1))
        }
        // 10->11 turns the signature written at v9 into that account's default rich-text signature.
        query("SELECT name, contentHtml, isDefault FROM signatures WHERE accountId = 'acct'").use { c ->
            assertTrue("10->11 must backfill the legacy per-account signature", c.moveToFirst())
            assertEquals("Signature", c.getString(0))
            assertEquals("Cheers,<br>Ada", c.getString(1))
            assertEquals(1, c.getInt(2))
            assertFalse("exactly one signature row must be backfilled", c.moveToNext())
        }
    }

    /** 15->16 drops the account tables from the cache (AccountDataMigrator copies them out first). */
    private fun SupportSQLiteDatabase.assertAccountTablesDroppedAtV16() {
        listOf("accounts", "credentials", "account_settings", "signatures").forEach { table ->
            query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = '$table'").use { c ->
                assertFalse("15->16 must drop `$table` from the cache database", c.moveToFirst())
            }
        }
    }

    private fun SupportSQLiteDatabase.count(table: String): Int = query("SELECT COUNT(*) FROM $table").use { c ->
        c.moveToFirst()
        c.getInt(0)
    }

    private companion object {
        const val TEST_DB = "migration-test.db"

        /** The oldest schema in app/schemas; earlier versions predate schema export. */
        const val OLDEST_EXPORTED_SCHEMA = 7

        /**
         * Every migration the app ships, pulled from Migrations.kt's file facade so a newly added
         * migration is replayed automatically. DatabaseModule.provideDatabase registers this same
         * set of top-level vals.
         */
        val allAppMigrations: List<Migration> = run {
            val migrationsFile = checkNotNull(MIGRATION_1_2.javaClass.enclosingClass) {
                "expected MIGRATION_1_2 to be a top-level val in Migrations.kt"
            }
            migrationsFile.methods
                .filter { Modifier.isStatic(it.modifiers) && it.parameterCount == 0 }
                .filter { Migration::class.java.isAssignableFrom(it.returnType) }
                .map { it.invoke(null) as Migration }
                .sortedBy { it.startVersion }
        }
    }
}
