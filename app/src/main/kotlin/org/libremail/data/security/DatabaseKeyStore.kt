// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.security

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.dbKeyDataStore: DataStore<Preferences> by preferencesDataStore(name = "libremail_dbkey")

/**
 * Supplies the SQLCipher passphrase for the opt-in encrypted cache. A random 256-bit key is
 * generated once and persisted only as ciphertext — sealed by the non-exportable Android Keystore
 * key via [KeystoreCrypto] — so the key that protects the cache is itself protected at rest. The
 * passphrase is returned as a 64-character hex string and used directly as the SQLCipher passphrase.
 *
 * Stored in its own DataStore (not the Room database it protects) to avoid a chicken-and-egg cycle.
 */
@Singleton
class DatabaseKeyStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val crypto: KeystoreCrypto,
) {
    private val generationLock = Mutex()

    /** Returns the cache passphrase, generating and sealing it on first use. */
    suspend fun passphrase(): String {
        existing()?.let { return it }
        return generationLock.withLock {
            // Re-check inside the lock so a concurrent first-caller doesn't generate a second key
            // (which would leave a DB encrypted under a key we then overwrite and can't reproduce).
            existing() ?: generateAndStore()
        }
    }

    private suspend fun existing(): String? =
        context.dbKeyDataStore.data.first()[SEALED_KEY]?.let { crypto.decrypt(it) }

    private suspend fun generateAndStore(): String {
        val raw = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        val hex = raw.joinToString("") { "%02x".format(it) }
        context.dbKeyDataStore.edit { it[SEALED_KEY] = crypto.encrypt(hex) }
        return hex
    }

    private companion object {
        val SEALED_KEY = stringPreferencesKey("sealed_db_key")
        const val KEY_BYTES = 32
    }
}
