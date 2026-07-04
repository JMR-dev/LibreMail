// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
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
            assertEquals(listOf("acct:1"), messageDao().observeSummaries().first().map { it.id })
            close()
        }

        // Decrypt back to plaintext and confirm the row is still there.
        DatabaseEncryption.ensurePlaintext(dbFile, passphrase)
        assertFalse("file must be plaintext again after decrypt", DatabaseEncryption.isEncrypted(dbFile))
        openPlaintext().apply {
            assertEquals(listOf("acct:1"), messageDao().observeSummaries().first().map { it.id })
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
            assertEquals(listOf("acct:1"), messageDao().observeSummaries().first().map { it.id })
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
            assertEquals(listOf("acct:1"), messageDao().observeSummaries().first().map { it.id })
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
