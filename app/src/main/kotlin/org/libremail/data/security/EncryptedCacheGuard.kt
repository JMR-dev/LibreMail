// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.security

import kotlinx.coroutines.flow.first
import org.libremail.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tells UI-less entry points (background sync/push/send) whether opening the Room cache would block
 * on the user authenticating. When app-lock AND encrypted-cache are on, the SQLCipher passphrase is
 * sealed by an auth-bound key and only lives in [PassphraseSession] after an unlock — so a worker
 * that constructs a DAO before then would park a thread indefinitely inside `provideDatabase`.
 *
 * Depends only on DataStore ([SettingsRepository]) and in-memory state ([PassphraseSession]) — never
 * on the Room database — so it is safe to consult *before* touching any DB-backed dependency.
 */
@Singleton
class EncryptedCacheGuard @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val session: PassphraseSession,
) {
    /**
     * True when the encrypted cache is currently locked, i.e. opening the database would suspend
     * waiting for the user to authenticate. Background work should defer (e.g. `Result.retry()`).
     */
    suspend fun isCacheLocked(): Boolean {
        val settings = settingsRepository.settings.first()
        return settings.appLock && settings.encryptCache && !session.isUnlocked()
    }
}
