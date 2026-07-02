// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local

import android.content.Context
import java.io.File

/** Central name and wipe helper for the Room cache database file (and its SQLite sidecars). */
object DatabaseFiles {

    const val NAME = "libremail.db"

    /**
     * The [org.libremail.data.local.AccountDatabase] file — accounts, credentials, per-account
     * settings and signatures. Deliberately a DIFFERENT file from [NAME] and NEVER wiped by [clear],
     * so a cache-key invalidation keeps the user signed in (issue #111).
     */
    const val ACCOUNTS_NAME = "libremail-accounts.db"

    /**
     * Delete the database and any WAL/SHM/journal sidecars. Call only when no connection is open —
     * used by the "clear + re-sync" path when the encryption key is invalidated and the encrypted
     * database can no longer be decrypted.
     */
    fun clear(context: Context) {
        val db = context.getDatabasePath(NAME)
        val dir = db.parentFile ?: return
        listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
            File(dir, db.name + suffix).delete()
        }
    }
}
