// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.data.local.coldopen.ColdOpenCacheProbe
import org.libremail.data.local.entity.MessageEntity
import java.io.File

/**
 * Issue #221: a **process-isolated** cold open of a pre-encrypted cache — the SQLCipher regression fixed
 * in 592a797. The crash only surfaced on a cold process opening an already-encrypted cache with nothing
 * to convert, where the keyed open reached `SQLiteConnection.nativeOpen` with the native `.so` unloaded
 * and threw `UnsatisfiedLinkError`. Every existing on-device test ([DatabaseEncryptionTest],
 * [DatabaseProvisionerInstrumentedTest], `DatabaseModuleInstrumentedTest`, `AccountDataMigratorTest`)
 * runs a conversion first, which loads the process-global library in-process — masking the bug exactly
 * as production did before the fix.
 *
 * Process isolation is unavoidable here: `System.loadLibrary` is process-global, so once THIS
 * (instrumentation) process mints the encrypted fixture, it can no longer observe a cold open. The
 * fixture is therefore opened in a separate `:coldopen` app process hosted by [ColdOpenCacheProbe] (a
 * debug-only [android.content.ContentProvider]); this test mints the fixture here — "a file created by a
 * prior encrypted DB instance" — and drives the cold open there via `ContentResolver.call`, which spins
 * that pristine process up on demand. The harness [ColdOpenCacheProbe.KEY_COLD_PROBE] check makes the
 * isolation self-verifying: if the library were already loaded in the harness process, this test fails
 * rather than passing a hollow assertion.
 */
@RunWith(AndroidJUnit4::class)
class ColdOpenEncryptedCacheTest {

    private val appContext = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "coldopen_encrypted_cache_test.db"
    private val dbFile: File get() = appContext.getDatabasePath(dbName)

    // 64 hex chars == a 32-byte SQLCipher passphrase, matching DatabaseKeyStore's format.
    private val passphrase = "0123456789abcdef".repeat(4)

    @Before
    @After
    fun clean() {
        appContext.deleteDatabase(dbName)
        dbFile.parentFile?.listFiles { f -> f.name.startsWith(dbName) }?.forEach { it.delete() }
    }

    @Test
    fun coldProcessOpensAPreEncryptedCacheWithoutUnsatisfiedLinkError() {
        // Mint a real SQLCipher-encrypted cache with one row HERE, in the instrumentation process. This
        // loads the process-global .so in THIS process — precisely why the cold open must be observed in a
        // different, pristine process.
        seedEncryptedFixture()
        assertTrue("precondition: the fixture is genuinely encrypted", DatabaseEncryption.isEncrypted(dbFile))

        val result = callColdOpenProcess()
        assertNotNull("the :coldopen process returned no result", result)
        val probe = result?.getString(ColdOpenCacheProbe.KEY_COLD_PROBE)
        val open = result?.getString(ColdOpenCacheProbe.KEY_OPEN)

        // The harness proves its own process is genuinely cold: a keyed open with no preceding
        // System.loadLibrary must fail at nativeOpen. Anything else means the .so was already loaded there
        // and the "cold" open would be meaningless — so fail loudly instead of passing a hollow assertion.
        assertEquals(
            "the :coldopen process was not actually cold (SQLCipher already loaded); isolation broke — open=$open",
            ColdOpenCacheProbe.PROBE_UNSATISFIED_LINK,
            probe,
        )

        // The headline #221 guard: opening the pre-encrypted cache through the production wiring on a cold
        // process succeeds (no UnsatisfiedLinkError) and reads the seeded row back.
        assertEquals(
            "cold open of the pre-encrypted cache failed (probe=$probe)",
            ColdOpenCacheProbe.OPEN_OK,
            open,
        )
    }

    private fun callColdOpenProcess(): Bundle? {
        val authority = appContext.packageName + ColdOpenCacheProbe.AUTHORITY_SUFFIX
        return appContext.contentResolver.call(
            Uri.parse("content://$authority"),
            ColdOpenCacheProbe.METHOD_COLD_OPEN,
            null,
            bundleOf(
                ColdOpenCacheProbe.KEY_DB_NAME to dbName,
                ColdOpenCacheProbe.KEY_PASSPHRASE to passphrase,
            ),
        )
    }

    /**
     * Builds a plaintext cache with one row and converts it to real SQLCipher ciphertext — the steady-
     * state shape an already-encrypted install presents at the next cold start, where `ensureEncrypted`
     * has nothing left to convert (592a797's exact failure mode).
     */
    private fun seedEncryptedFixture() {
        Room.databaseBuilder(appContext, LibreMailDatabase::class.java, dbName).build().apply {
            runBlocking { messageDao().insertNew(listOf(message(ColdOpenCacheProbe.EXPECTED_ROW_ID))) }
            close()
        }
        DatabaseEncryption.ensureEncrypted(dbFile, passphrase)
    }

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
