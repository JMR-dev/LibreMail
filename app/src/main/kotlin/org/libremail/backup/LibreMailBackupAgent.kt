// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.backup

import android.app.backup.BackupAgentHelper
import android.app.backup.FullBackupDataOutput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.libremail.data.settings.settingsDataStore
import org.libremail.data.settings.toAppSettings

/**
 * Enforces the runtime backup opt-in on top of Android Auto Backup.
 *
 * `android:allowBackup` is a manifest flag that can't be toggled at runtime, so the "include settings
 * in Android Backup" preference is enforced here instead: [onFullBackup] runs the backup only when the
 * user has opted in (the default is off, so no app data leaves the device). When opted in, it defers to
 * the framework, which applies the allowlist in `res/xml/data_extraction_rules.xml` (and
 * `res/xml/backup_rules.xml` on API < 31) — backing up the user-preferences DataStore only, never the
 * mail cache, the encrypted credentials, or the Keystore-sealed cache passphrase.
 *
 * Extends [BackupAgentHelper] (rather than raw `BackupAgent`) so the unused key/value backup/restore
 * paths inherit safe no-op implementations; only full-data backup is used (`fullBackupOnly=true`), and
 * full-data restore uses the default `onRestoreFile` handling.
 *
 * The opt-in flag is read directly from the shared [settingsDataStore] singleton so it does not depend
 * on Hilt or `Application.onCreate` having run in the framework's restricted backup mode.
 */
class LibreMailBackupAgent : BackupAgentHelper() {

    override fun onFullBackup(data: FullBackupDataOutput) {
        if (backupOptedIn()) {
            super.onFullBackup(data)
        }
    }

    /** Reads the opt-in flag; any failure defaults to "not opted in" so we never back up by accident. */
    private fun backupOptedIn(): Boolean = runCatching {
        runBlocking {
            BackupPolicy.shouldBackUp(applicationContext.settingsDataStore.data.first().toAppSettings())
        }
    }.getOrDefault(false)
}
