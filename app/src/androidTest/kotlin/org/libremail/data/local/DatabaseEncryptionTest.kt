// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.data.local.entity.MessageEntity
import org.libremail.reporting.AppLog
import org.libremail.reporting.RingLogBuffer
import java.io.File

/**
 * Round-trips the cache database through [DatabaseEncryption] (plaintext → encrypted → plaintext),
 * asserting the data and Room's schema version survive and the on-disk file is no longer readable
 * as plaintext once encrypted. Requires a device/emulator — it loads SQLCipher's native library.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseEncryptionTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "enc_roundtrip_test.db"
    private val dbFile: File get() = context.getDatabasePath(dbName)

    // 64 hex chars == a 32-byte SQLCipher passphrase.
    private val passphrase = "0123456789abcdef".repeat(4)

    @Before
    @After
    fun clean() {
        context.deleteDatabase(dbName)
        dbFile.parentFile?.listFiles { f -> f.name.startsWith(dbName) }?.forEach { it.delete() }
    }

    @Test
    fun encryptsDecryptsAndPreservesData() = runBlocking<Unit> {
        // Start with a plaintext Room database holding one row.
        openPlaintext().apply {
            messageDao().insertNew(listOf(message("acct:1")))
            close()
        }
        assertFalse("freshly created DB is plaintext", DatabaseEncryption.isEncrypted(dbFile))

        // Encrypt in place, then open through SQLCipher and confirm the row + schema survived.
        DatabaseEncryption.ensureEncrypted(dbFile, passphrase)
        assertTrue("file must not read as plaintext once encrypted", DatabaseEncryption.isEncrypted(dbFile))
        openEncrypted().apply {
            assertEquals("acct:1", messageDao().getById("acct:1")?.id)
            close()
        }

        // Decrypt back to plaintext and confirm the row is still there.
        DatabaseEncryption.ensurePlaintext(dbFile, passphrase)
        assertFalse("file must be plaintext again after decrypt", DatabaseEncryption.isEncrypted(dbFile))
        openPlaintext().apply {
            assertEquals("acct:1", messageDao().getById("acct:1")?.id)
            close()
        }
    }

    @Test
    fun isEncryptedIsFalseForMissingEmptyAndPlaintextFiles() = runBlocking<Unit> {
        val missing = File(dbFile.parentFile, "$dbName.missing")
        assertFalse("a non-existent file is not encrypted", DatabaseEncryption.isEncrypted(missing))

        val empty = File(dbFile.parentFile, "$dbName.empty")
        empty.delete()
        empty.createNewFile()
        assertFalse("a zero-length file is not encrypted", DatabaseEncryption.isEncrypted(empty))

        openPlaintext().apply {
            messageDao().insertNew(listOf(message("acct:1")))
            close()
        }
        assertFalse("a plaintext SQLite file is not encrypted", DatabaseEncryption.isEncrypted(dbFile))
    }

    @Test
    fun ensureEncryptedNoOpsOnMissingEmptyOrAlreadyEncryptedFiles() = runBlocking<Unit> {
        // Missing / empty: nothing to convert (the factory creates a fresh DB encrypted).
        val empty = File(dbFile.parentFile, "$dbName.empty")
        empty.createNewFile()
        DatabaseEncryption.ensureEncrypted(File(dbFile.parentFile, "$dbName.missing"), passphrase)
        DatabaseEncryption.ensureEncrypted(empty, passphrase)

        openPlaintext().apply {
            messageDao().insertNew(listOf(message("acct:1")))
            close()
        }
        DatabaseEncryption.ensureEncrypted(dbFile, passphrase)
        assertTrue(DatabaseEncryption.isEncrypted(dbFile))

        // A second call on an already-encrypted file is an idempotent no-op; the data stays readable.
        DatabaseEncryption.ensureEncrypted(dbFile, passphrase)
        assertTrue("the file stays encrypted", DatabaseEncryption.isEncrypted(dbFile))
        openEncrypted().apply {
            assertEquals("acct:1", messageDao().getById("acct:1")?.id)
            close()
        }
    }

    @Test
    fun ensurePlaintextNoOpsOnAnAlreadyPlaintextFile() = runBlocking<Unit> {
        openPlaintext().apply {
            messageDao().insertNew(listOf(message("acct:1")))
            close()
        }

        // Already plaintext: decrypt must be a no-op (and must not corrupt the file).
        DatabaseEncryption.ensurePlaintext(dbFile, passphrase)

        assertFalse(DatabaseEncryption.isEncrypted(dbFile))
        openPlaintext().apply {
            assertEquals("acct:1", messageDao().getById("acct:1")?.id)
            close()
        }
    }

    @Test
    fun conversionSweepsAStaleRollbackJournalSidecar() = runBlocking<Unit> {
        openPlaintext().apply {
            messageDao().insertNew(listOf(message("acct:1")))
            close()
        }
        // A stray `-journal` left next to the file by an interrupted rollback-journal-mode session. The
        // conversion runs in journal_mode = DELETE, so `-journal` is the sidecar that can actually linger
        // (the pre-existing sweep only removed `-wal`/`-shm`) — issue #313.
        val staleJournal = File(dbFile.parentFile, "$dbName-journal")
        staleJournal.outputStream().use { it.write(0) }
        assertTrue("precondition: a stale journal exists", staleJournal.exists())

        DatabaseEncryption.ensureEncrypted(dbFile, passphrase)

        assertFalse("the conversion must sweep the stale -journal sidecar", staleJournal.exists())
        openEncrypted().apply {
            assertEquals("the data still round-trips", "acct:1", messageDao().getById("acct:1")?.id)
            close()
        }
    }

    @Test
    fun schemaVersionIsCarriedOntoTheEncryptedFile() = runBlocking<Unit> {
        // A first Room open stamps PRAGMA user_version to the schema version; the conversion must carry
        // it across (sqlcipher_export copies tables but not that pragma), or Room would attempt a bogus
        // migration on the re-keyed file.
        openPlaintext().apply {
            messageDao().insertNew(listOf(message("acct:1")))
            close()
        }
        DatabaseEncryption.ensureEncrypted(dbFile, passphrase)

        val encrypted = SQLiteDatabase.openOrCreateDatabase(
            dbFile.absolutePath,
            passphrase.toByteArray(Charsets.US_ASCII),
            null,
            null,
        )
        val version = try {
            encrypted.version
        } finally {
            encrypted.close()
        }
        assertEquals("Room's schema version must survive the plaintext -> encrypted conversion", 20, version)
    }

    @Test
    fun conversionEmitsNonPiiAppLogBreadcrumbs() = runBlocking<Unit> {
        val buffer = RingLogBuffer()
        AppLog.install(buffer)

        // The seeded row carries an email address so the PII assertions below are meaningful.
        openPlaintext().apply {
            messageDao().insertNew(listOf(message("acct:1")))
            close()
        }

        DatabaseEncryption.ensureEncrypted(dbFile, passphrase)
        val afterEncrypt = buffer.snapshot()
        val converting = afterEncrypt.single { it.message.startsWith("converting local cache database") }
        assertEquals("the start breadcrumb is informational", 'I', converting.level)
        assertEquals("converting local cache database (targetEncrypted=true)", converting.message)
        val convertedAfterEncrypt = afterEncrypt.single { it.message == "local cache database converted" }
        assertEquals('D', convertedAfterEncrypt.level)

        // Converting back to plaintext logs the same pair with the flag flipped.
        buffer.clear()
        DatabaseEncryption.ensurePlaintext(dbFile, passphrase)
        val afterDecrypt = buffer.snapshot()
        assertTrue(
            afterDecrypt.any { it.message == "converting local cache database (targetEncrypted=false)" },
        )
        assertTrue(afterDecrypt.any { it.message == "local cache database converted" })

        // Neither conversion's breadcrumbs may leak the passphrase, the on-disk path, or account PII.
        (afterEncrypt + afterDecrypt).forEach { entry ->
            assertFalse("must not leak the passphrase", entry.message.contains(passphrase))
            assertFalse("must not leak the db file path", entry.message.contains(dbFile.absolutePath))
            assertFalse("must not leak the seeded email", entry.message.contains("ada@example.org"))
        }
    }

    private fun openPlaintext(): LibreMailDatabase =
        Room.databaseBuilder(context, LibreMailDatabase::class.java, dbName).build()

    private fun openEncrypted(): LibreMailDatabase =
        Room.databaseBuilder(context, LibreMailDatabase::class.java, dbName)
            .openHelperFactory(SupportOpenHelperFactory(passphrase.toByteArray(Charsets.US_ASCII), null, false))
            .build()

    private fun message(id: String) = MessageEntity(
        id = id,
        accountId = "acct",
        sender = "Ada",
        senderEmail = "ada@example.org",
        subject = "Hi",
        snippet = "",
        body = "",
        timestampMillis = 1_000L,
        isRead = false,
        isStarred = false,
    )
}
