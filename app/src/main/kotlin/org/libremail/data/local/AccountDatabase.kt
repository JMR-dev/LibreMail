// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import org.libremail.data.local.dao.AccountDao
import org.libremail.data.local.dao.AccountSettingsDao
import org.libremail.data.local.dao.CredentialDao
import org.libremail.data.local.dao.SignatureDao
import org.libremail.data.local.entity.AccountEntity
import org.libremail.data.local.entity.AccountSettingsEntity
import org.libremail.data.local.entity.CredentialEntity
import org.libremail.data.local.entity.SignatureEntity

/**
 * Durable store for the pieces of an account that must survive a mail-cache wipe (issue #111): the
 * account itself, its sealed credential, per-account settings, and saved signatures.
 *
 * This lives in its OWN database file ([DatabaseFiles.ACCOUNTS_NAME]) that is deliberately NEVER
 * bound to the auth-bound SQLCipher key. When app-lock + encrypted-cache are on and that key is
 * invalidated (a genuine biometric re-enrollment or lock removal/re-add), only the mail cache
 * ([LibreMailDatabase]) becomes undecryptable and is wiped; this database is untouched, so the user
 * stays signed in instead of being dropped back into onboarding.
 *
 * It is plaintext on disk. The only secret it holds is [CredentialEntity.encryptedSecret], which is
 * already AES-GCM ciphertext sealed at the column level by the non-auth
 * [org.libremail.data.security.KeystoreCrypto] master key (and that key survives an auth-key
 * invalidation), so the secret never touches disk in the clear regardless of this file's own
 * encryption. Account metadata (email address, server hosts) is not a secret. Keeping the file
 * plaintext is what makes it maximally resilient — it can always be opened without any Keystore key,
 * so no key invalidation can ever strand it.
 *
 * Existing installs are migrated into this database once, at startup, by [AccountDataMigrator]
 * before [MIGRATION_15_16] drops the moved tables from the cache database.
 */
@Database(
    entities = [
        AccountEntity::class,
        CredentialEntity::class,
        AccountSettingsEntity::class,
        SignatureEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AccountDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun credentialDao(): CredentialDao
    abstract fun accountSettingsDao(): AccountSettingsDao
    abstract fun signatureDao(): SignatureDao
}
