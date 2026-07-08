// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local

import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.libremail.reporting.AppLog
import java.io.File

/**
 * Converts the Room database file between plaintext and SQLCipher-encrypted form, in place and
 * idempotently. It is meant to run at startup — before Room opens the file — so there is never an
 * open connection to race with. The on-disk form is detected from the file header (a plaintext
 * SQLite file starts with the 16-byte magic "SQLite format 3"; an encrypted one does not),
 * so the conversion self-heals: it re-runs after an interrupted attempt and no-ops when already in
 * the desired form.
 */
object DatabaseEncryption {

    /** True when the file exists and is NOT a plaintext SQLite database (i.e. it is encrypted). */
    fun isEncrypted(dbFile: File): Boolean =
        dbFile.exists() && dbFile.length() >= SQLITE_HEADER.size && !startsWithSqliteHeader(dbFile)

    /** Ensures [dbFile] is SQLCipher-encrypted with [passphrase]; converts an existing plaintext DB. */
    fun ensureEncrypted(dbFile: File, passphrase: String) {
        if (!dbFile.exists() || dbFile.length() == 0L) return // fresh DB: the factory creates it encrypted
        if (!startsWithSqliteHeader(dbFile)) return // already encrypted
        migrate(dbFile, sourcePassphrase = "", targetPassphrase = passphrase)
    }

    /** Ensures [dbFile] is plaintext; decrypts an existing SQLCipher DB using [passphrase]. */
    fun ensurePlaintext(dbFile: File, passphrase: String) {
        if (!dbFile.exists() || dbFile.length() == 0L) return
        if (startsWithSqliteHeader(dbFile)) return // already plaintext
        migrate(dbFile, sourcePassphrase = passphrase, targetPassphrase = "")
    }

    /**
     * Copies the database into a sibling temp file in the target form via `sqlcipher_export`, then
     * atomically swaps it into place. An empty passphrase means "no encryption" on that side. Room's
     * schema version (`PRAGMA user_version`) is carried across manually — `sqlcipher_export` copies
     * tables but not that pragma, and a reset version would make Room attempt a bogus migration.
     */
    private fun migrate(dbFile: File, sourcePassphrase: String, targetPassphrase: String) {
        AppLog.i(TAG, "converting local cache database (targetEncrypted=${targetPassphrase.isNotEmpty()})")
        ensureNativeLibraryLoaded()
        val dir = dbFile.parentFile ?: error("database file has no parent directory")
        val tmp = File(dir, dbFile.name + ".migrate").apply { delete() }

        val userVersion: Int
        val source = SQLiteDatabase.openOrCreateDatabase(
            dbFile.absolutePath,
            sourcePassphrase.toByteArray(Charsets.US_ASCII),
            null, // no CursorFactory
            null, // no DatabaseErrorHandler
        )
        try {
            userVersion = source.version // PRAGMA user_version — Room's schema version
            source.rawExecSQL("PRAGMA journal_mode = DELETE;") // fold any WAL back into the main file
            val keyLiteral = targetPassphrase.replace("'", "''")
            source.rawExecSQL("ATTACH DATABASE '${tmp.absolutePath}' AS target KEY '$keyLiteral';")
            source.rawExecSQL("SELECT sqlcipher_export('target');")
            source.rawExecSQL("DETACH DATABASE target;")
        } finally {
            source.close()
        }

        // `sqlcipher_export` copies tables but not PRAGMA user_version; carry Room's schema version
        // onto the exported file (on its own main schema — a schema-qualified PRAGMA is rejected) so
        // Room doesn't see version 0 and attempt a bogus migration.
        check(tmp.length() > 0L) { "sqlcipher_export produced no output database" }
        val target = SQLiteDatabase.openOrCreateDatabase(
            tmp.absolutePath,
            targetPassphrase.toByteArray(Charsets.US_ASCII),
            null,
            null,
        )
        try {
            target.rawExecSQL("PRAGMA journal_mode = DELETE;") // no WAL sidecars to orphan on swap
            target.version = userVersion
        } finally {
            target.close()
        }

        // Swap the converted file into place; drop any stale WAL/SHM sidecars from either file first.
        listOf(dbFile.name, tmp.name).forEach { base ->
            File(dir, "$base-wal").delete()
            File(dir, "$base-shm").delete()
        }
        if (!tmp.renameTo(dbFile)) {
            tmp.copyTo(dbFile, overwrite = true)
            tmp.delete()
        }
        AppLog.d(TAG, "local cache database converted")
    }

    private fun startsWithSqliteHeader(dbFile: File): Boolean {
        val head = ByteArray(SQLITE_HEADER.size)
        val read = dbFile.inputStream().use { it.read(head) }
        return read == SQLITE_HEADER.size && head.contentEquals(SQLITE_HEADER)
    }

    @Volatile private var libraryLoaded = false

    /**
     * Load SQLCipher's native library once. Public so other startup helpers that open a database via
     * [net.zetetic.database.sqlcipher.SQLiteDatabase] before Room does (e.g. [AccountDataMigrator])
     * can guarantee it is loaded first.
     */
    fun ensureNativeLibraryLoaded() {
        if (libraryLoaded) return
        synchronized(this) {
            if (!libraryLoaded) {
                System.loadLibrary("sqlcipher")
                libraryLoaded = true
            }
        }
    }

    /**
     * Opens a throwaway keyed SQLCipher database next to [cacheFile] and immediately closes it, purely to
     * reach `SQLiteConnection.nativeOpen` — the exact call site of the #359 crash — from inside
     * [DatabaseProvisioner]'s fail-closed handler. [ensureNativeLibraryLoaded]
     * (`System.loadLibrary("sqlcipher")`) can succeed on a device whose bundled `.so` is otherwise
     * incompatible, yet the JNI-bound `nativeOpen` still be unresolved; that only surfaces when a keyed
     * database is actually opened, which Room does LATER via its deferred open helper
     * ([org.libremail.di.DatabaseModule]) — outside any handler. Probing the real open here lets the
     * provisioner catch that [LinkageError] and fail closed BEFORE Room reaches it.
     *
     * Deliberately opens a sibling throwaway file (never the real cache) so it can never create, mutate,
     * or leave `-wal`/`-shm` sidecars on the cache, then deletes the probe and its sidecars in a `finally`.
     * Mirrors `SqlCipherOpenSpikeTest`'s stage-B keyed-open probe (issue #359).
     *
     * PRECONDITION: the caller must have already loaded the native library (via [ensureNativeLibraryLoaded]).
     * The sole production caller — [DatabaseProvisioner]'s encrypted branch — does so on the line above its
     * probe call. Kept out of here on purpose so the provisioner loads the library exactly ONCE per open
     * (the `ensureNativeLibraryLoaded()`-exactly-once invariant its instrumented tests pin), not twice.
     */
    fun probeKeyedOpen(cacheFile: File, passphrase: String) {
        val dir = cacheFile.parentFile ?: error("cache database file has no parent directory")
        val probe = File(dir, cacheFile.name + PROBE_SUFFIX)
        try {
            SQLiteDatabase.openOrCreateDatabase(
                probe.absolutePath,
                passphrase.toByteArray(Charsets.US_ASCII),
                null, // no CursorFactory
                null, // no DatabaseErrorHandler
            ).close()
        } finally {
            listOf("", "-wal", "-shm", "-journal").forEach { File(dir, probe.name + it).delete() }
        }
    }

    private const val TAG = "LibreMailDbCrypto"

    // Suffix of the throwaway file [probeKeyedOpen] opens to reach nativeOpen without touching the cache.
    private const val PROBE_SUFFIX = ".openprobe"

    // The 16-byte magic that opens every plaintext SQLite file: "SQLite format 3" + a NUL terminator.
    // Spelled out as bytes to keep the trailing NUL unambiguous.
    private val SQLITE_HEADER = byteArrayOf(
        0x53, 0x51, 0x4C, 0x69, 0x74, 0x65, 0x20, 0x66,
        0x6F, 0x72, 0x6D, 0x61, 0x74, 0x20, 0x33, 0x00,
    )
}
