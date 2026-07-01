// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.security

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dbKeyDataStore: DataStore<Preferences> by preferencesDataStore(name = "libremail_dbkey")

/**
 * Supplies the SQLCipher passphrase for the opt-in encrypted cache. A random 256-bit key is
 * generated once and persisted only as ciphertext — sealed by a non-exportable Android Keystore key
 * — so the key that protects the cache is itself protected at rest. The passphrase is returned as a
 * 64-character hex string and used directly as the SQLCipher passphrase.
 *
 * The passphrase can be sealed two ways, but never both at once:
 *  - [SEALED_MASTER]: by the non-auth [KeystoreCrypto] master key — auto-unwraps, used when app-lock
 *    is OFF (unchanged legacy behavior).
 *  - [SEALED_AUTH]: by the auth-bound [DatabaseKeyCipher] — only unwrappable after the user passes a
 *    `BiometricPrompt`, used when app-lock is ON. The unwrapped value lives in [PassphraseSession].
 *
 * Stored in its own DataStore (not the Room database it protects) to avoid a chicken-and-egg cycle.
 */
@Singleton
class DatabaseKeyStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val crypto: KeystoreCrypto,
    private val authCipher: DatabaseKeyCipher,
    private val session: PassphraseSession,
) {
    private val generationLock = Mutex()

    /**
     * App-lock OFF path: returns the passphrase sealed by the master key, generating and sealing it
     * on first use. This is the original auto-unwrap behavior and must only be used when app-lock is
     * disabled (when it is enabled the master-sealed copy is intentionally removed).
     */
    suspend fun passphrase(): String {
        masterSealed()?.let { return it }
        return generationLock.withLock {
            // Re-check inside the lock so a concurrent first-caller doesn't generate a second key
            // (which would leave a DB encrypted under a key we then overwrite and can't reproduce).
            masterSealed() ?: generateAndSealMaster()
        }
    }

    /** True when a passphrase sealed by the auth-bound key is stored (app-lock is armed). */
    suspend fun hasAuthSealedPassphrase(): Boolean = read(SEALED_AUTH) != null

    /**
     * Unwrap the auth-sealed passphrase into [PassphraseSession]. Call right after a successful
     * unlock (within the key's auth-validity window). No-op if nothing is auth-sealed yet. Throws
     * [android.security.keystore.KeyPermanentlyInvalidatedException] if the key was invalidated.
     */
    suspend fun unlockWithAuth() {
        val sealed = read(SEALED_AUTH) ?: return
        session.unlock(authCipher.decrypt(sealed))
    }

    /**
     * Enable app-lock: (re)seal the passphrase with the auth-bound key and drop the master-sealed
     * copy so the cache key is no longer recoverable without authentication. Requires a valid auth
     * window (call right after a successful `BiometricPrompt`). Reuses the existing passphrase when
     * present so an already-encrypted cache stays readable; otherwise mints a fresh one. Unlocks the
     * session with the resulting value.
     */
    suspend fun sealWithAuth(): Unit = generationLock.withLock {
        val plain = masterSealed() ?: session.current() ?: generateHex()
        val sealed = authCipher.encrypt(plain)
        context.dbKeyDataStore.edit {
            it[SEALED_AUTH] = sealed
            it.remove(SEALED_MASTER)
        }
        session.unlock(plain)
    }

    /**
     * Disable app-lock: reseal the passphrase with the master key and drop the auth-sealed copy so
     * the cache opens without authentication again. Requires the passphrase to be available — the
     * session must be unlocked (i.e. the user authenticated earlier this session), which is always
     * true while the user is inside the (gated) settings screen.
     */
    suspend fun sealWithMaster(): Unit = generationLock.withLock {
        val plain = session.current() ?: read(SEALED_AUTH)?.let { authCipher.decrypt(it) } ?: return@withLock
        context.dbKeyDataStore.edit {
            it[SEALED_MASTER] = crypto.encrypt(plain)
            it.remove(SEALED_AUTH)
        }
        session.lock()
    }

    /**
     * Drop every sealed copy of the passphrase and delete the auth-bound key. Used when the key is
     * invalidated (re-enrollment / lock removal) and the encrypted cache is being wiped: a fresh
     * passphrase is generated on the next [sealWithAuth] / [passphrase]. Callers MUST wipe the
     * encrypted database file in the same operation, otherwise it becomes permanently unreadable.
     */
    suspend fun resetSealedPassphrase(): Unit = generationLock.withLock {
        context.dbKeyDataStore.edit {
            it.remove(SEALED_AUTH)
            it.remove(SEALED_MASTER)
        }
        authCipher.deleteKey()
        session.lock()
    }

    /**
     * Persist that the encrypted cache must be wiped on the next cold start. The actual file deletion
     * happens in `DatabaseModule.provideDatabase` (via [consumeClearPending]) BEFORE Room opens the
     * database, so there is never an open connection whose backing file is deleted underneath it —
     * the corruption-safe way to "clear + re-sync" after a screen-lock change invalidates the key.
     */
    suspend fun setClearPending() {
        context.dbKeyDataStore.edit { it[CLEAR_PENDING] = true }
    }

    /** Read-and-clear the clear-pending flag. Returns true if the cache should be wiped now. */
    suspend fun consumeClearPending(): Boolean {
        val pending = context.dbKeyDataStore.data.first()[CLEAR_PENDING] == true
        if (pending) context.dbKeyDataStore.edit { it.remove(CLEAR_PENDING) }
        return pending
    }

    private suspend fun masterSealed(): String? = read(SEALED_MASTER)?.let { crypto.decrypt(it) }

    private suspend fun read(key: Preferences.Key<String>): String? = context.dbKeyDataStore.data.first()[key]

    private suspend fun generateAndSealMaster(): String {
        val hex = generateHex()
        context.dbKeyDataStore.edit { it[SEALED_MASTER] = crypto.encrypt(hex) }
        return hex
    }

    private fun generateHex(): String {
        val raw = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        return raw.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        // Key name kept as the original "sealed_db_key" so existing installs' master-sealed
        // passphrase is read back unchanged after upgrade.
        val SEALED_MASTER = stringPreferencesKey("sealed_db_key")
        val SEALED_AUTH = stringPreferencesKey("sealed_db_key_auth")
        val CLEAR_PENDING = booleanPreferencesKey("clear_encrypted_cache_pending")
        const val KEY_BYTES = 32
    }
}
