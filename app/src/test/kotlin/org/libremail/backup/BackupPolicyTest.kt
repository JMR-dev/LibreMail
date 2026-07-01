// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.backup

import org.junit.Test
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
    fun `the credentials and mail-cache database is never eligible for backup`() {
        assertTrue(BackupPolicy.EXCLUDED_DATABASE_PATHS.contains("libremail.db"))
        // WAL/SHM/journal side-files can hold recently written rows too.
        assertTrue(BackupPolicy.EXCLUDED_DATABASE_PATHS.contains("libremail.db-wal"))
        assertTrue(BackupPolicy.EXCLUDED_DATABASE_PATHS.contains("libremail.db-shm"))
    }
}
