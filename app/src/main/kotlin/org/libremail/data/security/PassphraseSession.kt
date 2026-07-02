// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory holder for the decrypted SQLCipher passphrase during an unlocked session.
 *
 * When app-lock is enabled the database passphrase is sealed by an auth-bound Keystore key
 * ([DatabaseKeyCipher]) and is only recoverable after the user passes a `BiometricPrompt`. The
 * unwrapped passphrase is kept here for the unlocked session — never persisted. `DatabaseModule`
 * reads it through [await] so the encrypted database is opened only after authentication.
 *
 * Eviction — current limitation: the passphrase is NOT guaranteed to leave the process when the app
 * re-locks. [lock] is called on app-lock disable and cache reset ([DatabaseKeyStore]), but it is
 * deliberately NOT called on the app-lock UI gate's grace-expiry/timeout re-lock, and even if it were
 * it would only drop THIS holder's reference:
 *  - the value is an immutable [String] and cannot be zeroed in place — [lock] merely releases it
 *    for GC;
 *  - once the encrypted cache has been opened, SQLCipher keeps the key inside the already-open
 *    Room/native handle. `DatabaseModule.provideDatabase` runs once per process, so nothing here
 *    closes that handle; the key stays resident for the process lifetime regardless of [lock];
 *  - this holder's unlocked-ness also drives [EncryptedCacheGuard], so clearing it while the app is
 *    merely locked (not exited) would stall background sync/push even though the DB is still open.
 *
 * Truly evicting the passphrase on lock/timeout therefore requires a database close/reopen mechanism,
 * which is owned by the DB-lifecycle work in issues #93 (provideDatabase) and #111 (DB
 * re-architecture) and is intentionally out of scope here. Until those land, do not rely on [lock]
 * for cryptographic erasure of the passphrase from the process.
 */
@Singleton
class PassphraseSession @Inject constructor() {

    private val passphrase = MutableStateFlow<String?>(null)

    /** Store the unwrapped passphrase after a successful unlock. */
    fun unlock(value: String) {
        passphrase.value = value
    }

    /**
     * Drop this holder's reference to the passphrase (on app-lock disable / cache reset). See the
     * class KDoc: this is a partial eviction only — it does not close the open database, so SQLCipher
     * keeps the key resident for the process lifetime (full eviction is deferred to #93 / #111).
     */
    fun lock() {
        passphrase.value = null
    }

    /** The current passphrase, or null while locked. */
    fun current(): String? = passphrase.value

    fun isUnlocked(): Boolean = passphrase.value != null

    /** Suspend until the session is unlocked, then return the passphrase. */
    suspend fun await(): String = passphrase.filterNotNull().first()
}
