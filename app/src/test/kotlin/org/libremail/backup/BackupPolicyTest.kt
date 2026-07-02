// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.backup

import org.junit.Test
import org.libremail.data.local.DatabaseFiles
import org.libremail.data.settings.AppSettings
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupPolicyTest {

    @Test
    fun `backup is off by default`() {
        assertFalse(AppSettings().includeInBackup, "the opt-in default must be off")
        assertFalse(BackupPolicy.shouldBackUp(AppSettings()), "no backup runs without opting in")
    }

    @Test
    fun `backup runs only when the user opts in`() {
        assertTrue(BackupPolicy.shouldBackUp(AppSettings(includeInBackup = true)))
        assertFalse(BackupPolicy.shouldBackUp(AppSettings(includeInBackup = false)))
    }

    @Test
    fun `only the settings datastore is eligible for backup`() {
        assertEquals("datastore/libremail_settings.preferences_pb", BackupPolicy.SAFE_SETTINGS_FILE)
        // The safe file must not be, or resemble, a secret store.
        assertFalse(BackupPolicy.SAFE_SETTINGS_FILE.contains("dbkey"))
    }

    @Test
    fun `the keystore-sealed db key is never eligible for backup`() {
        assertTrue(
            BackupPolicy.EXCLUDED_FILE_PATHS.any { it.contains("libremail_dbkey") },
            "the sealed cache passphrase DataStore must be excluded",
        )
    }

    @Test
    fun `the mail-cache database is never eligible for backup`() {
        assertTrue(BackupPolicy.EXCLUDED_DATABASE_PATHS.contains("libremail.db"))
        // WAL/SHM/journal side-files can hold recently written rows too.
        assertTrue(BackupPolicy.EXCLUDED_DATABASE_PATHS.contains("libremail.db-wal"))
        assertTrue(BackupPolicy.EXCLUDED_DATABASE_PATHS.contains("libremail.db-shm"))
        assertTrue(BackupPolicy.EXCLUDED_DATABASE_PATHS.contains("libremail.db-journal"))
    }

    @Test
    fun `the accounts and credentials database is never eligible for backup`() {
        // Since #111 the accounts + encrypted IMAP/OAuth credentials live in their OWN database file
        // (libremail-accounts.db), so it must be in the never-back-up set just like the cache.
        assertTrue(BackupPolicy.EXCLUDED_DATABASE_PATHS.contains("libremail-accounts.db"))
        assertTrue(BackupPolicy.EXCLUDED_DATABASE_PATHS.contains("libremail-accounts.db-wal"))
        assertTrue(BackupPolicy.EXCLUDED_DATABASE_PATHS.contains("libremail-accounts.db-shm"))
        assertTrue(BackupPolicy.EXCLUDED_DATABASE_PATHS.contains("libremail-accounts.db-journal"))
    }

    @Test
    fun `excluded database paths are derived from DatabaseFiles so none can silently fall out`() {
        val derived = DatabaseFiles.fileNames(DatabaseFiles.NAME) +
            DatabaseFiles.fileNames(DatabaseFiles.ACCOUNTS_NAME)
        // Derived, not hand-maintained: the exclusion set is exactly the DatabaseFiles-known files —
        // no more (nothing stale) and no less (every DB + sidecar covered).
        assertEquals(derived, BackupPolicy.EXCLUDED_DATABASE_PATHS)
        listOf(DatabaseFiles.NAME, DatabaseFiles.ACCOUNTS_NAME).forEach { name ->
            DatabaseFiles.fileNames(name).forEach { path ->
                assertTrue(path in BackupPolicy.EXCLUDED_DATABASE_PATHS, "$path must never be backed up")
            }
        }
    }
}
