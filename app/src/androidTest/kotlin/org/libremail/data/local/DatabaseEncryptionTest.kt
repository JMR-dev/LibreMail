// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
            assertEquals(listOf("acct:1"), messageDao().observeAll().first().map { it.id })
            close()
        }

        // Decrypt back to plaintext and confirm the row is still there.
        DatabaseEncryption.ensurePlaintext(dbFile, passphrase)
        assertFalse("file must be plaintext again after decrypt", DatabaseEncryption.isEncrypted(dbFile))
        openPlaintext().apply {
            assertEquals(listOf("acct:1"), messageDao().observeAll().first().map { it.id })
            close()
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
