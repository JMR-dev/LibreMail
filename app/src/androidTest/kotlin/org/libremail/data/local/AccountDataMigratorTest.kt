// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.data.local.entity.CredentialEntity
import org.libremail.reporting.AppLog
import org.libremail.reporting.RingLogBuffer

/**
 * The one-time move performed by [AccountDataMigrator] (issue #111): copying accounts / credentials /
 * per-account settings / signatures out of the cache database into the plaintext [AccountDatabase].
 *
 * Exercises the [AccountDataMigrator.copyAccountTables] core directly (the full [AccountDataMigrator]
 * also resolves the passphrase and flips the done-flag, which need the real DataStore/Keystore). A v14
 * cache is built with [MigrationTestHelper] from the exported schema, so the copy runs against exactly
 * the on-disk shape an upgrading user has.
 */
@RunWith(AndroidJUnit4::class)
class AccountDataMigratorTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LibreMailDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val cacheName = "acct-migrator-cache-test.db"
    private val accountsName = "acct-migrator-accounts-test.db"
    private val cacheFile get() = context.getDatabasePath(cacheName)
    private val accountsFile get() = context.getDatabasePath(accountsName)

    // 64 hex chars == a 32-byte SQLCipher passphrase.
    private val passphrase = "0123456789abcdef".repeat(4)

    @Before
    @After
    fun clean() {
        listOf(cacheName, accountsName).forEach { name ->
            context.deleteDatabase(name)
            context.getDatabasePath(name).parentFile
                ?.listFiles { f -> f.name.startsWith(name) }
                ?.forEach { it.delete() }
        }
    }

    /** Builds a v14 cache holding one fully-populated account plus a mail row. */
    private fun seedVersion14Cache() {
        helper.createDatabase(cacheName, 14).apply {
            execSQL(
                "INSERT INTO accounts (id, email, displayName, authType, imap_host, imap_port, imap_security, " +
                    "smtp_host, smtp_port, smtp_security) VALUES ('acct', 'ada@example.org', 'Ada', " +
                    "'PASSWORD_IMAP', 'imap.example.org', 993, 'SSL_TLS', 'smtp.example.org', 465, 'SSL_TLS')",
            )
            execSQL("INSERT INTO credentials (accountId, encryptedSecret) VALUES ('acct', 'sealed-secret')")
            execSQL(
                "INSERT INTO account_settings (accountId, signature, signatureEnabled, notificationsEnabled, " +
                    "retentionCount, retentionMonths) VALUES ('acct', 'Cheers', 1, 0, NULL, 6)",
            )
            execSQL(
                "INSERT INTO signatures (id, accountId, name, contentHtml, isDefault) " +
                    "VALUES ('sig-1', 'acct', 'Work', '<p>Regards</p>', 1)",
            )
            execSQL(
                "INSERT INTO messages (id, accountId, sender, senderEmail, subject, snippet, body, isHtml, " +
                    "timestampMillis, isRead, isStarred, folder, inInbox, bodyFetched, uid) VALUES " +
                    "('acct:INBOX:1', 'acct', 'Ada', 'a@x', 'Hi', '', '', 0, 1, 0, 0, 'INBOX', 1, 0, 1)",
            )
            close()
        }
    }

    private fun openAccountsDb(): AccountDatabase =
        Room.databaseBuilder(context, AccountDatabase::class.java, accountsName).build()

    @Test
    fun movesEveryAccountTableOutOfAPlaintextCache() = runBlocking<Unit> {
        seedVersion14Cache()

        AccountDataMigrator.copyAccountTables(cacheFile, cachePassphrase = "", accountsFile = accountsFile)

        // First open lets Room stamp its identity onto the migrator-created file; reopen so a real
        // session's reads run against a fully Room-owned database.
        openAccountsDb().apply {
            assertEquals("Ada", accountDao().getById("acct")?.displayName)
            close()
        }
        openAccountsDb().apply {
            val account = accountDao().getById("acct")
            assertEquals("ada@example.org", account?.email)
            assertEquals(993, account?.imap?.port)
            assertEquals("smtp.example.org", account?.smtp?.host)
            assertEquals("sealed-secret", credentialDao().getById("acct")?.encryptedSecret)
            val settings = accountSettingsDao().get("acct")
            // Seeded signatureEnabled = 1, notificationsEnabled = 0: both booleans must round-trip.
            assertEquals(true, settings?.signatureEnabled)
            assertEquals(false, settings?.notificationsEnabled)
            assertEquals(6, settings?.retentionMonths)
            assertNull(settings?.retentionCount)
            val signatures = signatureDao().observeForAccount("acct").first()
            assertEquals(listOf("Work"), signatures.map { it.name })
            assertTrue("the default flag must round-trip", signatures.single().isDefault)
            close()
        }
    }

    @Test
    fun movesAccountsOutOfAnEncryptedCache() = runBlocking<Unit> {
        seedVersion14Cache()
        // Turn the cache into the SQLCipher form an app-lock + encrypted-cache user has on disk.
        DatabaseEncryption.ensureEncrypted(cacheFile, passphrase)
        assertTrue("precondition: the source cache is encrypted", DatabaseEncryption.isEncrypted(cacheFile))

        AccountDataMigrator.copyAccountTables(cacheFile, cachePassphrase = passphrase, accountsFile = accountsFile)

        openAccountsDb().apply {
            assertNotNull(accountDao().getById("acct"))
            close()
        }
        openAccountsDb().apply {
            assertEquals("ada@example.org", accountDao().getById("acct")?.email)
            assertEquals("sealed-secret", credentialDao().getById("acct")?.encryptedSecret)
            close()
        }
    }

    @Test
    fun copyEmitsANonPiiAppLogBreadcrumbNamingOnlyTheMovedTables() = runBlocking<Unit> {
        seedVersion14Cache()
        val buffer = RingLogBuffer()
        AppLog.install(buffer)

        AccountDataMigrator.copyAccountTables(cacheFile, cachePassphrase = "", accountsFile = accountsFile)

        val entry = buffer.snapshot()
            .single { it.message.startsWith("moved account tables into the account database") }
        assertEquals("the migration breadcrumb is a debug line", 'D', entry.level)
        listOf("accounts", "credentials", "account_settings", "signatures").forEach { table ->
            assertTrue("breadcrumb must name the moved table $table", entry.message.contains(table))
        }
        // The breadcrumb carries only table names — never the seeded email, secret, or passphrase.
        assertFalse(entry.message.contains("ada@example.org"))
        assertFalse(entry.message.contains("sealed-secret"))
        assertFalse(entry.message.contains(passphrase))
    }

    @Test
    fun reRunningTheCopyIsIdempotentAndKeepsLaterEdits() = runBlocking<Unit> {
        seedVersion14Cache()
        AccountDataMigrator.copyAccountTables(cacheFile, cachePassphrase = "", accountsFile = accountsFile)

        // Simulate the user editing an account AFTER the migration.
        openAccountsDb().apply {
            val edited = accountDao().getById("acct")!!.copy(displayName = "Ada Lovelace")
            accountDao().upsert(edited)
            close()
        }

        // A re-run (e.g. after a mid-startup crash before the done-flag was set) must not clobber it.
        AccountDataMigrator.copyAccountTables(cacheFile, cachePassphrase = "", accountsFile = accountsFile)

        openAccountsDb().apply {
            assertEquals(1, accountDao().getAll().size)
            assertEquals(
                "INSERT OR IGNORE must not overwrite the post-migration edit",
                "Ada Lovelace",
                accountDao().getById("acct")?.displayName,
            )
            close()
        }
    }

    @Test
    fun accountsAndCredentialsSurviveACacheWipe() = runBlocking<Unit> {
        seedVersion14Cache()
        AccountDataMigrator.copyAccountTables(cacheFile, cachePassphrase = "", accountsFile = accountsFile)

        // The "clear + re-sync" recovery wipes only the cache file; AccountDatabase is a separate file.
        context.deleteDatabase(cacheName)
        assertTrue("precondition: the cache file is gone", !cacheFile.exists())

        openAccountsDb().apply {
            assertNotNull("the account must outlive a cache wipe (issue #111)", accountDao().getById("acct"))
            assertEquals("sealed-secret", credentialDao().getById("acct")?.encryptedSecret)
            // And it is still usable: a fresh credential can be written with no cache present.
            credentialDao().upsert(CredentialEntity("acct", "rotated"))
            assertEquals("rotated", credentialDao().getById("acct")?.encryptedSecret)
            close()
        }
    }

    @Test
    fun copiesFromACacheOlderThanTheCurrentSchema() = runBlocking<Unit> {
        // A cache last written at v12 — before account_settings gained retentionCount/retentionMonths
        // (v13). The copy must not choke on the columns the destination has but the source lacks
        // (a device upgrade from an old install crashed the migrator here).
        helper.createDatabase(cacheName, 12).apply {
            execSQL(
                "INSERT INTO accounts (id, email, displayName, authType, imap_host, imap_port, imap_security, " +
                    "smtp_host, smtp_port, smtp_security) VALUES ('acct', 'ada@example.org', 'Ada', " +
                    "'PASSWORD_IMAP', 'imap.example.org', 993, 'SSL_TLS', 'smtp.example.org', 465, 'SSL_TLS')",
            )
            execSQL("INSERT INTO credentials (accountId, encryptedSecret) VALUES ('acct', 'sealed-secret')")
            execSQL(
                "INSERT INTO account_settings (accountId, signature, signatureEnabled, notificationsEnabled) " +
                    "VALUES ('acct', 'Sig', 0, 1)",
            )
            close()
        }

        AccountDataMigrator.copyAccountTables(cacheFile, cachePassphrase = "", accountsFile = accountsFile)

        openAccountsDb().apply {
            assertEquals("ada@example.org", accountDao().getById("acct")?.email)
            assertEquals("sealed-secret", credentialDao().getById("acct")?.encryptedSecret)
            val settings = accountSettingsDao().get("acct")
            assertEquals(false, settings?.signatureEnabled)
            assertEquals(true, settings?.notificationsEnabled)
            // Columns the v12 source lacked come across as the destination's defaults (null).
            assertNull("retentionCount absent from a v12 cache must default to null", settings?.retentionCount)
            assertNull(settings?.retentionMonths)
            close()
        }
    }

    @Test
    fun copiedAccountsGetAStableAlphabeticalInitialSortOrder() = runBlocking<Unit> {
        // Insert three accounts in non-alphabetical order so the assertion proves the initial order is
        // by email (the pre-#111 listing) rather than the copy's insertion order (issue #164). sortOrder
        // is a destination-only column the v14 cache never had, so it is assigned entirely by the copy.
        helper.createDatabase(cacheName, 14).apply {
            listOf("c" to "carol@example.org", "a" to "ada@example.org", "b" to "bob@example.org")
                .forEach { (id, email) ->
                    execSQL(
                        "INSERT INTO accounts (id, email, displayName, authType, imap_host, imap_port, " +
                            "imap_security, smtp_host, smtp_port, smtp_security) VALUES " +
                            "('$id', '$email', '$id', 'PASSWORD_IMAP', 'imap.example.org', 993, 'SSL_TLS', " +
                            "'smtp.example.org', 465, 'SSL_TLS')",
                    )
                }
            close()
        }

        AccountDataMigrator.copyAccountTables(cacheFile, cachePassphrase = "", accountsFile = accountsFile)

        openAccountsDb().apply {
            // getAll orders by sortOrder; the copy ranked by email, so ada(0) < bob(1) < carol(2).
            assertEquals(listOf("a", "b", "c"), accountDao().getAll().map { it.id })
            close()
        }
    }

    @Test
    fun migratorDdlMatchesExportedAccountDatabaseSchema() {
        val schema = JSONObject(
            InstrumentationRegistry.getInstrumentation().context.assets
                .open("org.libremail.data.local.AccountDatabase/2.json")
                .bufferedReader().use { it.readText() },
        ).getJSONObject("database")
        val entities = schema.getJSONArray("entities")

        var checkedIndex = false
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            val expectedCreate = entity.getString("createSql").replace("\${TABLE_NAME}", table)
            assertEquals(
                "AccountDataMigrator DDL for `$table` must match the exported AccountDatabase schema",
                expectedCreate,
                AccountDataMigrator.CREATE_TABLE_SQL[table],
            )
            if (entity.has("indices")) {
                val indices = entity.getJSONArray("indices")
                for (j in 0 until indices.length()) {
                    val index = indices.getJSONObject(j)
                    if (index.getString("name") == "index_signatures_accountId") {
                        assertEquals(
                            "AccountDataMigrator signatures index must match the exported schema",
                            index.getString("createSql").replace("\${TABLE_NAME}", table),
                            AccountDataMigrator.SIGNATURES_INDEX_SQL,
                        )
                        checkedIndex = true
                    }
                }
            }
        }
        assertEquals(
            "every migrator table DDL must correspond to an exported entity",
            AccountDataMigrator.CREATE_TABLE_SQL.keys,
            (0 until entities.length()).map { entities.getJSONObject(it).getString("tableName") }.toSet(),
        )
        assertTrue("the signatures index must be present in the exported schema", checkedIndex)
    }
}
