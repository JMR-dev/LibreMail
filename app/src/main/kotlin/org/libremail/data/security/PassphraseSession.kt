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
 * unwrapped passphrase is kept here for the lifetime of the unlocked session — never persisted — and
 * cleared on lock, timeout, or when app-lock is disabled. `DatabaseModule` reads it through [await]
 * so the encrypted database is opened only after authentication.
 *
 * The value is an immutable [String] to stay consistent with the rest of the passphrase plumbing; it
 * cannot be zeroed in place, which is an accepted limitation of the existing design.
 */
@Singleton
class PassphraseSession @Inject constructor() {

    private val passphrase = MutableStateFlow<String?>(null)

    /** Store the unwrapped passphrase after a successful unlock. */
    fun unlock(value: String) {
        passphrase.value = value
    }

    /** Clear the passphrase from memory (on lock, timeout, or app-lock disable). */
    fun lock() {
        passphrase.value = null
    }

    /** The current passphrase, or null while locked. */
    fun current(): String? = passphrase.value

    fun isUnlocked(): Boolean = passphrase.value != null

    /** Suspend until the session is unlocked, then return the passphrase. */
    suspend fun await(): String = passphrase.filterNotNull().first()
}
