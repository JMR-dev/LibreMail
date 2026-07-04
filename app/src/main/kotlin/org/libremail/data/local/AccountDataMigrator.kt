// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.libremail.data.security.DatabaseKeyStore
import org.libremail.data.settings.SettingsRepository
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private val Context.accountMigrationDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "libremail_account_migration")

/**
 * One-time, crash-safe move of the account tables (`accounts`, `credentials`, `account_settings`,
 * `signatures`) out of the auth-bound cache database [LibreMailDatabase] into the non-auth
 * [AccountDatabase] (issue #111). Runs at startup, driven by `DatabaseModule.provideDatabase`, BEFORE
 * Room opens the cache and its [MIGRATION_15_16] drops the moved tables.
 *
 * ### Why not a Room migration
 * The copy is cross-database, so it needs `ATTACH DATABASE`, which SQLite forbids inside the
 * transaction Room wraps every migration in. It therefore runs here on a dedicated SQLCipher
 * connection before Room opens either database.
 *
 * ### Handling the encrypted source
 * When the opt-in encrypted cache is on, the source `libremail.db` is SQLCipher-encrypted. The
 * caller resolves and hands us its passphrase (the same one Room uses to open it); we attach the
 * cache with that passphrase and copy into a plaintext `libremail-accounts.db`. When the cache is
 * plaintext the passphrase is empty. Reading the source's schema validates the passphrase, so a
 * genuinely wrong key fails loudly here (the same open would fail in Room) rather than losing data.
 *
 * The unrecoverable-key case does not reach us: `provideDatabase` wipes an undecryptable cache (and
 * resets its seals) BEFORE calling us, so we then see a fresh/empty cache with nothing to move — the
 * accounts trapped in that already-invalidated cache are lost regardless (the pre-existing bug), but
 * no future invalidation can strand them again once they live in [AccountDatabase].
 *
 * ### Crash-safety & idempotency
 *  - We never drop the source here; [MIGRATION_15_16] does that after we return, so if we crash the
 *    source rows are still intact for the next attempt.
 *  - The copy uses `INSERT OR IGNORE`, so a re-run after a mid-copy crash converges (existing rows
 *    are skipped, never duplicated, and never overwrite anything the user changed post-migration).
 *  - The "done" flag is only set after a successful copy; until then every start retries. Once set we
 *    return immediately and never touch the cache passphrase again — so after migration the account
 *    database opens with no Keystore dependency at all.
 */
@Singleton
class AccountDataMigrator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyStore: DatabaseKeyStore,
    private val settingsRepository: SettingsRepository,
) {

    /**
     * Copy the account tables into [AccountDatabase] if it has not been done yet. Idempotent and
     * safe to call from every `provideDatabase` construction. Throws (rather than silently skipping)
     * on an unexpected copy failure so the caller does not proceed to drop the source tables — a
     * crash-loop that preserves data is strictly safer than a wipe that loses it.
     */
    suspend fun migrateIfNeeded() {
        if (isDone()) return
        val cacheFile = context.getDatabasePath(DatabaseFiles.NAME)
        if (cacheFile.exists() && cacheFile.length() > 0L) {
            // Read the cache in its CURRENT on-disk form. `provideDatabase` runs us before it converts
            // between plaintext and encrypted, so the key is empty unless the file is encrypted now.
            val cacheKey = if (DatabaseEncryption.isEncrypted(cacheFile)) {
                keyStore.resolvePassphrase(settingsRepository.settings.first().appLock)
            } else {
                ""
            }
            val accountsFile = context.getDatabasePath(DatabaseFiles.ACCOUNTS_NAME)
            withContext(Dispatchers.IO) { copyAccountTables(cacheFile, cacheKey, accountsFile) }
        }
        markDone()
    }

    private suspend fun isDone(): Boolean = context.accountMigrationDataStore.data.first()[DONE] == true

    private suspend fun markDone() {
        context.accountMigrationDataStore.edit { it[DONE] = true }
    }

    companion object {
        private const val TAG = "LibreMailAcctMigrate"
        private val DONE = booleanPreferencesKey("accounts_moved_out_of_cache")

        /** The account tables, parent before children so foreign keys never block an insert. */
        private val TABLES = listOf("accounts", "credentials", "account_settings", "signatures")

        /**
         * DDL for the account tables in [AccountDatabase] v1, copied verbatim from the exported Room
         * schema (`schemas/org.libremail.data.local.AccountDatabase/1.json`). It MUST stay byte-for-byte
         * identical to what Room generates for those entities, or Room silently accepts a subtly wrong
         * schema (its identity check only compares the hash it writes, not the pre-existing tables).
         * `AccountDataMigratorTest.migratorDdlMatchesExportedAccountDatabaseSchema` guards it against the
         * exported schema; `internal` only so that test can read it.
         */
        internal val CREATE_TABLE_SQL = mapOf(
            "accounts" to
                "CREATE TABLE IF NOT EXISTS `accounts` (`id` TEXT NOT NULL, `email` TEXT NOT NULL, " +
                "`displayName` TEXT NOT NULL, `authType` TEXT NOT NULL, " +
                "`sortOrder` INTEGER NOT NULL DEFAULT 0, `imap_host` TEXT NOT NULL, " +
                "`imap_port` INTEGER NOT NULL, `imap_security` TEXT NOT NULL, `smtp_host` TEXT NOT NULL, " +
                "`smtp_port` INTEGER NOT NULL, `smtp_security` TEXT NOT NULL, PRIMARY KEY(`id`))",
            "credentials" to
                "CREATE TABLE IF NOT EXISTS `credentials` (`accountId` TEXT NOT NULL, " +
                "`encryptedSecret` TEXT NOT NULL, PRIMARY KEY(`accountId`))",
            "account_settings" to
                "CREATE TABLE IF NOT EXISTS `account_settings` (`accountId` TEXT NOT NULL, " +
                "`signature` TEXT NOT NULL, `signatureEnabled` INTEGER NOT NULL, " +
                "`notificationsEnabled` INTEGER NOT NULL, `retentionCount` INTEGER, " +
                "`retentionMonths` INTEGER, PRIMARY KEY(`accountId`), " +
                "FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
            "signatures" to
                "CREATE TABLE IF NOT EXISTS `signatures` (`id` TEXT NOT NULL, `accountId` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, `contentHtml` TEXT NOT NULL, `isDefault` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`), FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )

        internal const val SIGNATURES_INDEX_SQL =
            "CREATE INDEX IF NOT EXISTS `index_signatures_accountId` ON `signatures` (`accountId`)"

        /**
         * Copies the account tables from [cacheFile] (opened with [cachePassphrase]; empty = plaintext)
         * into a plaintext [accountsFile], creating the destination schema first. Opens the destination
         * as `main` and attaches the (possibly encrypted) cache as `cache`, so a plaintext connection
         * can still read the encrypted source via SQLCipher's per-attach key. Visible for the migrator
         * test; call [migrateIfNeeded] in production.
         */
        internal fun copyAccountTables(cacheFile: File, cachePassphrase: String, accountsFile: File) {
            DatabaseEncryption.ensureNativeLibraryLoaded()
            val db = SQLiteDatabase.openOrCreateDatabase(
                accountsFile.absolutePath,
                "".toByteArray(Charsets.US_ASCII), // destination is plaintext
                null,
                null,
            )
            try {
                // No WAL: keep the destination in rollback-journal mode (as DatabaseEncryption does)
                // so that after close there is no -wal/-shm holding uncommitted rows for Room to miss.
                db.rawExecSQL("PRAGMA journal_mode = DELETE;")
                val keyLiteral = cachePassphrase.replace("'", "''")
                val cachePath = cacheFile.absolutePath.replace("'", "''")
                db.rawExecSQL("ATTACH DATABASE '$cachePath' AS cache KEY '$keyLiteral';")
                try {
                    val present = presentTables(db)
                    if (present.isEmpty()) return // fresh cache or already dropped: nothing to move
                    TABLES.forEach { db.rawExecSQL(CREATE_TABLE_SQL.getValue(it)) }
                    db.rawExecSQL(SIGNATURES_INDEX_SQL)
                    // Copy by explicit shared column names, never SELECT *: the on-disk cache may predate
                    // columns the current schema added (e.g. account_settings gained retentionCount /
                    // retentionMonths at v13), and a bare SELECT * would then supply fewer values than the
                    // destination has columns and fail the whole migration. Listing the columns the source
                    // actually has lets the destination's newer columns take their defaults (NULL). Parent
                    // first so an enforced foreign key would still be satisfied; INSERT OR IGNORE is idempotent.
                    TABLES.filter { it in present }.forEach { table ->
                        val cols = sharedColumns(db, table)
                        db.rawExecSQL("INSERT OR IGNORE INTO `$table` ($cols) SELECT $cols FROM cache.`$table`")
                    }
                    // sortOrder (issue #164) is a destination-only column the pre-#111 cache never had, so
                    // the copy above leaves every account at its DEFAULT 0. Give them the same stable
                    // alphabetical initial order ACCOUNT_MIGRATION_1_2 assigns (rank by email), so a
                    // pre-#111 upgrade lands in the order it already showed rather than an undefined
                    // tie-break. Post-#111 installs migrate via ACCOUNT_MIGRATION_1_2 instead and never
                    // reach this copy; either way the user can then drag to reorder.
                    if ("accounts" in present) {
                        db.rawExecSQL(
                            "UPDATE `accounts` SET `sortOrder` = " +
                                "(SELECT COUNT(*) FROM `accounts` AS ranked WHERE ranked.`email` < `accounts`.`email`)",
                        )
                    }
                    Log.d(TAG, "moved account tables into the account database: $present")
                } finally {
                    db.rawExecSQL("DETACH DATABASE cache;")
                }
            } finally {
                db.close()
            }
            // Room opens the destination next; drop any sidecars the copy left so a stale WAL/SHM can't
            // confuse its first open.
            val dir = accountsFile.parentFile
            if (dir != null) {
                listOf("-wal", "-shm", "-journal").forEach { File(dir, accountsFile.name + it).delete() }
            }
        }

        private fun presentTables(db: SQLiteDatabase): Set<String> {
            val names = TABLES.joinToString(",") { "'$it'" }
            val present = mutableSetOf<String>()
            db.rawQuery(
                "SELECT name FROM cache.sqlite_master WHERE type = 'table' AND name IN ($names)",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) present += cursor.getString(0)
            }
            return present
        }

        /**
         * Column names present in BOTH the freshly-created destination `$table` (always the current
         * schema) and the source `cache.$table` (possibly an older on-disk schema), backtick-quoted and
         * comma-joined for an INSERT/SELECT column list. Destination-only columns are omitted so they
         * take their defaults instead of overflowing the value list.
         */
        private fun sharedColumns(db: SQLiteDatabase, table: String): String {
            val source = tableColumns(db, "cache", table)
            return tableColumns(db, "main", table)
                .filter { it in source }
                .joinToString(", ") { "`$it`" }
        }

        /** The column names of `$schema.$table`, in declared order, via `PRAGMA table_info`. */
        private fun tableColumns(db: SQLiteDatabase, schema: String, table: String): List<String> {
            val columns = mutableListOf<String>()
            db.rawQuery("PRAGMA $schema.table_info(`$table`)", null).use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
            }
            return columns
        }
    }
}
