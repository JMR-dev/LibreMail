// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local

import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.data.local.entity.MessageEntity
import org.libremail.reporting.AppLog
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * SPIKE for issue #359 — characterizes, on a real device, whether opening the opt-in SQLCipher-encrypted
 * cache throws `UnsatisfiedLinkError` because the bundled `libsqlcipher.so` is not compatible with the
 * device's memory **page size**. Android 15+/SDK-37 devices may run **16 KB pages**; a native `.so` not
 * built/aligned for 16 KB pages fails to load (`dlopen`) or to bind its JNI methods, surfacing as
 * `UnsatisfiedLinkError` at `System.loadLibrary("sqlcipher")` or at `SQLiteConnection.nativeOpen`.
 *
 * This is investigation-only: it does not change production behaviour. It runs the exact #359 open path in
 * three independent stages so a failing run tells us **which** stage breaks, and it records the device page
 * size ([Os.sysconf] `_SC_PAGESIZE`) so a pass/fail can be tied to 4 KB vs 16 KB pages. It is meant to be
 * run twice on the SAME device — once in 4 KB mode, once in 16 KB mode (Pixel Developer Options toggle) —
 * to give a definitive A/B: if every stage passes at 4 KB and fails at 16 KB, 16 KB pages are the cause.
 *
 * On success each stage asserts the encrypted DB genuinely opens and round-trips a row. On failure each
 * stage re-raises the full throwable — class, message (which `.so`), whether a [LinkageError] is in the
 * cause chain, the page size, and the complete stack trace — so the A/B report captures the real cause
 * rather than a bare assertion. All data is synthetic; nothing logged or asserted is PII.
 */
@RunWith(AndroidJUnit4::class)
class SqlCipherOpenSpikeTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "sqlcipher_spike_test.db"
    private val dbFile: File get() = context.getDatabasePath(dbName)
    private val probeDbName = "sqlcipher_spike_probe.db"
    private val probeDbFile: File get() = context.getDatabasePath(probeDbName)

    // 64 hex chars == a 32-byte SQLCipher passphrase, matching DatabaseKeyStore's format.
    private val passphrase = "0123456789abcdef".repeat(4)

    @Before
    @After
    fun clean() {
        listOf(dbName, probeDbName).forEach { name ->
            context.deleteDatabase(name)
            context.getDatabasePath(name).parentFile
                ?.listFiles { f -> f.name.startsWith(name) }
                ?.forEach { it.delete() }
        }
    }

    /**
     * Stage A — load the SQLCipher native library the way production does
     * ([DatabaseEncryption.ensureNativeLibraryLoaded] -> `System.loadLibrary("sqlcipher")`). This is the
     * first place a 16 KB-incompatible `.so` can fail (`dlopen` rejects an unaligned library).
     */
    @Test
    fun stageA_sqlCipherNativeLibraryLoads() {
        AppLog.i(TAG, "stageA start: $environment")
        try {
            DatabaseEncryption.ensureNativeLibraryLoaded()
        } catch (t: Throwable) {
            surface("A/loadLibrary(\"sqlcipher\")", t)
        }
        AppLog.i(TAG, "stageA PASS: SQLCipher native library loaded; $environment")
    }

    /**
     * Stage B — reach `SQLiteConnection.nativeOpen`: after loading the library (as production does), open a
     * keyed SQLCipher database and round-trip a row through the cipher. This is the exact call site named in
     * the #359 crash (`UnsatisfiedLinkError … SQLiteConnection.nativeOpen`).
     */
    @Test
    fun stageB_keyedNativeOpenSucceeds() {
        AppLog.i(TAG, "stageB start: $environment")
        try {
            DatabaseEncryption.ensureNativeLibraryLoaded()
            val db = SQLiteDatabase.openOrCreateDatabase(
                probeDbFile.absolutePath,
                passphrase.toByteArray(Charsets.US_ASCII),
                null, // no CursorFactory
                null, // no DatabaseErrorHandler
            )
            try {
                db.execSQL("CREATE TABLE IF NOT EXISTS spike(x INTEGER)")
                db.execSQL("INSERT INTO spike(x) VALUES (42)")
                db.rawQuery("SELECT x FROM spike LIMIT 1", null).use { cursor ->
                    assertTrue("keyed DB returned no row", cursor.moveToFirst())
                    assertEquals("keyed DB round-trip mismatch", 42, cursor.getInt(0))
                }
            } finally {
                db.close()
            }
        } catch (t: Throwable) {
            surface("B/SQLiteConnection.nativeOpen (keyed open)", t)
        }
        AppLog.i(TAG, "stageB PASS: keyed nativeOpen + round-trip OK; $environment")
    }

    /**
     * Stage C — the full #359 production path: create a plaintext Room cache with one row, convert it to
     * SQLCipher ciphertext ([DatabaseEncryption.ensureEncrypted]), load the library, then reopen the cache
     * through Room's [SupportOpenHelperFactory] (exactly [org.libremail.di.DatabaseModule]'s encrypted open
     * lambda) and read the seeded row back.
     */
    @Test
    fun stageC_encryptedRoomCacheOpensThroughProductionFactory() {
        AppLog.i(TAG, "stageC start: $environment")
        try {
            Room.databaseBuilder(context, LibreMailDatabase::class.java, dbName).build().apply {
                runBlocking { messageDao().insertNew(listOf(message("acct:1"))) }
                close()
            }
            DatabaseEncryption.ensureEncrypted(dbFile, passphrase)
            assertTrue("precondition: fixture must be genuinely encrypted", DatabaseEncryption.isEncrypted(dbFile))
            DatabaseEncryption.ensureNativeLibraryLoaded()

            val database = Room.databaseBuilder(context, LibreMailDatabase::class.java, dbName)
                .openHelperFactory(SupportOpenHelperFactory(passphrase.toByteArray(Charsets.US_ASCII), null, false))
                .build()
            try {
                val ids = runBlocking { database.messageDao().observeSummaries().first().map { it.id } }
                assertEquals("encrypted cache did not read the seeded row back", listOf("acct:1"), ids)
            } finally {
                database.close()
            }
        } catch (t: Throwable) {
            surface("C/Room encrypted cache open (SupportOpenHelperFactory)", t)
        }
        AppLog.i(TAG, "stageC PASS: encrypted Room cache opened through production factory; $environment")
    }

    /** A one-line, PII-free description of the device + page size every stage stamps into its log/report. */
    private val environment: String
        get() = "PAGE_SIZE=${pageSizeBytes()} bytes (16384 => 16 KB pages), SDK=${Build.VERSION.SDK_INT}, " +
            "release=${Build.VERSION.RELEASE}, abis=${Build.SUPPORTED_ABIS.joinToString(",")}"

    private fun pageSizeBytes(): Long = Os.sysconf(OsConstants._SC_PAGESIZE)

    /**
     * Fails the stage while surfacing the complete cause so the on-device A/B report captures the real
     * `UnsatisfiedLinkError` (which `.so`, full stack trace, page size) instead of a bare assertion.
     */
    private fun surface(stage: String, t: Throwable): Nothing {
        val stack = StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString()
        val chain = buildString {
            var current: Throwable? = t
            while (current != null) {
                append("\n    - ").append(current.javaClass.name).append(": ").append(current.message)
                current = current.cause
            }
        }
        val diagnostic = buildString {
            append("SQLCipher spike stage '").append(stage).append("' FAILED on this device.")
            append("\n  ").append(environment)
            append("\n  LinkageError in cause chain = ").append(hasLinkageError(t))
            append("\n  cause chain:").append(chain)
            append("\n  full stack trace:\n").append(stack)
        }
        AppLog.e(TAG, "SQLCipher spike stage '$stage' FAILED; $environment", t)
        throw AssertionError(diagnostic, t)
    }

    private fun hasLinkageError(throwable: Throwable): Boolean {
        var current: Throwable? = throwable
        while (current != null) {
            if (current is LinkageError) return true
            current = current.cause
        }
        return false
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

    private companion object {
        const val TAG = "SqlCipherOpenSpike"
    }
}
