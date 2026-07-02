// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local

import android.content.Context

/** Central names and wipe helper for the Room database files (and their SQLite sidecars). */
object DatabaseFiles {

    const val NAME = "libremail.db"

    /**
     * The [org.libremail.data.local.AccountDatabase] file — accounts, credentials, per-account
     * settings and signatures. Deliberately a DIFFERENT file from [NAME] and NEVER wiped by [clear],
     * so a cache-key invalidation keeps the user signed in (issue #111).
     */
    const val ACCOUNTS_NAME = "libremail-accounts.db"

    /**
     * The statically-nameable SQLite sidecars that accompany a database file. A transient `-mj*`
     * master journal can also exist, but its suffix is random and so can't be listed by name —
     * [clear] leans on [Context.deleteDatabase] to sweep that one up.
     */
    private val SIDECAR_SUFFIXES = listOf("-wal", "-shm", "-journal")

    /**
     * [name] plus each of its statically-nameable sidecars. The single source of truth for which
     * on-disk files make up a database file; `BackupPolicy` derives its never-back-up set from this
     * so a new database (or a new sidecar suffix) can never silently fall out of the exclusions.
     */
    fun fileNames(name: String): List<String> = listOf(name) + SIDECAR_SUFFIXES.map { name + it }

    /**
     * Delete the cache database ([NAME]) and every sidecar — including the `-mj*` master journal a
     * hand-rolled suffix list would miss — via [Context.deleteDatabase]. NEVER touches
     * [ACCOUNTS_NAME], so a cache-key invalidation keeps the user signed in (issue #111). Call only
     * when no connection is open — used by the "clear + re-sync" path when the encryption key is
     * invalidated and the encrypted database can no longer be decrypted.
     */
    fun clear(context: Context) {
        context.deleteDatabase(NAME)
    }
}
