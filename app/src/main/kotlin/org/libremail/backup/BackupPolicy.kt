// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.backup

import org.libremail.data.settings.AppSettings

/**
 * Single source of truth for what LibreMail is willing to hand to Android Backup. Kept in lockstep
 * with `res/xml/data_extraction_rules.xml` (API 31+) and `res/xml/backup_rules.xml` (API 29-30); the
 * path constants here are asserted against those resources by `DataExtractionRulesTest`.
 *
 * Only re-creatable user preferences are eligible. The mail cache re-downloads on the next sync, and
 * the credentials plus the Keystore-sealed cache passphrase are device-bound secrets that would only
 * ever restore as undecryptable ciphertext — so they are never backed up.
 */
object BackupPolicy {

    /** `filesDir`-relative DataStore file holding user preferences — the only data we back up. */
    const val SAFE_SETTINGS_FILE: String = "datastore/libremail_settings.preferences_pb"

    /** `filesDir`-relative paths that must never leave the device. */
    val EXCLUDED_FILE_PATHS: List<String> = listOf(
        // Keystore-sealed SQLCipher passphrase for the encrypted cache: the wrapping key is
        // non-exportable and device-bound, so this ciphertext is useless anywhere else.
        "datastore/libremail_dbkey.preferences_pb",
    )

    /** `databases`-dir-relative names that must never leave the device (encrypted credentials + mail cache). */
    val EXCLUDED_DATABASE_PATHS: List<String> = listOf(
        "libremail.db",
        "libremail.db-wal",
        "libremail.db-shm",
        "libremail.db-journal",
    )

    /**
     * Whether Android Backup may run for this app. Opt-in and OFF by default: nothing is backed up
     * (or transferred device-to-device) unless the user has explicitly enabled it in Settings.
     */
    fun shouldBackUp(settings: AppSettings): Boolean = settings.includeInBackup
}
