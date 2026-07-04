// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local.coldopen

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.libremail.data.local.DatabaseEncryption
import org.libremail.data.local.DeferredOpenHelperFactory
import org.libremail.data.local.LibreMailDatabase

/**
 * Test-only harness for issue #221. Lives in the **debug** source set (never in a release APK) and is
 * declared in the debug manifest with `android:process=":coldopen"`, so its work runs in a SEPARATE app
 * process from the instrumentation process.
 *
 * SQLCipher's `System.loadLibrary("sqlcipher")` is process-global: once the instrumentation process mints
 * the encrypted fixture (or any earlier test opens a keyed database), the `.so` is loaded there and a
 * later "cold open" can no longer be observed in that process. That is the exact blind spot behind the
 * 592a797 crash — a cold start opening an already-encrypted cache with nothing to convert, where the
 * keyed open reaches `SQLiteConnection.nativeOpen` with the library unloaded and throws
 * `UnsatisfiedLinkError`. This provider gives the test a pristine process to reproduce it.
 *
 * [call] runs in the `:coldopen` process and reports two things back in a [Bundle]:
 *  1. [KEY_COLD_PROBE] — proof the process is genuinely cold: a keyed open with NO preceding
 *     `System.loadLibrary` must fail at `nativeOpen`. Anything else means the `.so` was already loaded
 *     here, so the isolation premise broke and the "cold" open below would be meaningless.
 *  2. [KEY_OPEN] — the result of opening the pre-encrypted cache exactly the way production does on a
 *     steady-state encrypted start ([DatabaseProvisioner]'s encrypted branch + [DatabaseModule]'s open
 *     lambda): [DatabaseEncryption.ensureEncrypted] (a no-op on an already-encrypted file),
 *     [DatabaseEncryption.ensureNativeLibraryLoaded] (the 592a797 fix), then a Room open through a
 *     [DeferredOpenHelperFactory] wrapping [SupportOpenHelperFactory]. Reports whether the seeded row
 *     reads back.
 *
 * The `DatabaseProvisioner`/`DatabaseModule` objects themselves are not invoked here because they require
 * MockK-substituted collaborators (`DatabaseKeyStore`/`SettingsRepository`/`AccountDataMigrator` are
 * final and read the real Keystore/DataStore), and the test APK — hence MockK — is not on a forked app
 * process's classloader. The provisioner's encrypted-branch decision is instead mirrored line-for-line,
 * against the real `DatabaseEncryption`, `DeferredOpenHelperFactory` and `SupportOpenHelperFactory`.
 */
class ColdOpenCacheProbe : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val out = Bundle()
        if (method != METHOD_COLD_OPEN) {
            out.putString(KEY_ERROR, "unknown method: $method")
            return out
        }
        val appContext = requireNotNull(context).applicationContext
        val bundle = requireNotNull(extras) { "cold-open call requires extras" }
        val dbName = requireNotNull(bundle.getString(KEY_DB_NAME)) { "missing $KEY_DB_NAME" }
        val passphrase = requireNotNull(bundle.getString(KEY_PASSPHRASE)) { "missing $KEY_PASSPHRASE" }

        out.putString(KEY_COLD_PROBE, probeKeyedOpenWithoutLoad(appContext))
        out.putString(KEY_OPEN, openThroughProductionWiring(appContext, dbName, passphrase))
        return out
    }

    /**
     * A keyed open with NO preceding `System.loadLibrary` — the exact call the pre-592a797 provisioner
     * reached with the `.so` unloaded. In a genuinely cold process this hits `nativeOpen` and throws
     * [UnsatisfiedLinkError]. Runs against a THROWAWAY file so a partially-created database can never
     * disturb the real fixture opened afterwards.
     */
    private fun probeKeyedOpenWithoutLoad(appContext: Context): String {
        val probeName = "coldopen_probe_$PROBE_NONCE.db"
        return try {
            val helper = SupportOpenHelperFactory(PROBE_KEY.toByteArray(Charsets.US_ASCII), null, false)
                .create(noOpConfiguration(appContext, probeName))
            try {
                helper.writableDatabase // forces the keyed nativeOpen — the 592a797 crash surface
                PROBE_OPENED_UNEXPECTEDLY
            } finally {
                runCatching { helper.close() }
            }
        } catch (t: Throwable) {
            if (hasUnsatisfiedLink(t)) PROBE_UNSATISFIED_LINK else "$PROBE_OTHER${t.javaClass.name}"
        } finally {
            appContext.deleteDatabase(probeName)
        }
    }

    /**
     * Opens the pre-encrypted fixture the same way production does on a steady-state encrypted start,
     * against the real production classes. Returns [OPEN_OK] iff the keyed open succeeds cold and the
     * seeded row reads back.
     */
    private fun openThroughProductionWiring(appContext: Context, dbName: String, passphrase: String): String {
        val dbFile = appContext.getDatabasePath(dbName)
        return try {
            // Mirror DatabaseProvisioner's encrypted branch: the conversion is a no-op on an already-
            // encrypted file, so the explicit native-library load is what lets the keyed open succeed.
            DatabaseEncryption.ensureEncrypted(dbFile, passphrase)
            DatabaseEncryption.ensureNativeLibraryLoaded()
            val keyBytes = passphrase.toByteArray(Charsets.US_ASCII)
            val database = Room.databaseBuilder(appContext, LibreMailDatabase::class.java, dbName)
                // Mirror DatabaseModule.provideDatabase's encrypted open lambda exactly.
                .openHelperFactory(
                    DeferredOpenHelperFactory { configuration ->
                        SupportOpenHelperFactory(keyBytes, null, false).create(configuration)
                    },
                )
                .build()
            try {
                val ids = runBlocking { database.messageDao().observeSummaries().first().map { it.id } }
                if (ids == listOf(EXPECTED_ROW_ID)) OPEN_OK else "$OPEN_ROWS$ids"
            } finally {
                database.close()
            }
        } catch (t: Throwable) {
            "$OPEN_FAIL${t.javaClass.name}: ${t.message}"
        }
    }

    private fun noOpConfiguration(appContext: Context, name: String): SupportSQLiteOpenHelper.Configuration =
        SupportSQLiteOpenHelper.Configuration.builder(appContext)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(CALLBACK_VERSION) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

    // Unused ContentProvider surface — this provider exists only for its process-isolated call().
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        0

    companion object {
        /** Appended to the app's `applicationId` to form the provider authority (see the debug manifest). */
        const val AUTHORITY_SUFFIX = ".coldopen"
        const val METHOD_COLD_OPEN = "coldOpen"

        const val KEY_DB_NAME = "dbName"
        const val KEY_PASSPHRASE = "passphrase"
        const val KEY_COLD_PROBE = "coldProbe"
        const val KEY_OPEN = "open"
        const val KEY_ERROR = "error"

        /** The id of the single row the fixture is seeded with; the cold open must read it back. */
        const val EXPECTED_ROW_ID = "acct:1"

        const val PROBE_UNSATISFIED_LINK = "UNSATISFIED_LINK"
        const val PROBE_OPENED_UNEXPECTEDLY = "OPENED_UNEXPECTEDLY"
        const val PROBE_OTHER = "OTHER:"
        const val OPEN_OK = "OK"
        const val OPEN_FAIL = "FAIL:"
        const val OPEN_ROWS = "ROWS:"

        private const val PROBE_KEY = "coldopenprobe"
        private const val CALLBACK_VERSION = 1
        private val PROBE_NONCE = System.nanoTime()

        private fun hasUnsatisfiedLink(throwable: Throwable): Boolean {
            var current: Throwable? = throwable
            while (current != null) {
                if (current is UnsatisfiedLinkError) return true
                current = current.cause
            }
            return false
        }
    }
}
