// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.security

import android.content.Context
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import org.libremail.data.local.DatabaseEncryption
import org.libremail.data.local.DatabaseFiles
import org.libremail.data.settings.SettingsRepository
import org.libremail.reporting.AppLog
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tells UI-less entry points (background sync/push/send) whether opening the Room cache would block
 * on the user authenticating — a worker that constructs a DAO while it would must defer
 * (`Result.retry()`) instead of parking a thread indefinitely inside `provideDatabase`.
 *
 * The answer mirrors [DatabaseKeyStore.resolvePassphrase]'s blocking branches: it is keyed off which
 * seal actually EXISTS ([DatabaseKeyStore.sealState]), never off the appLock/encryptCache settings
 * alone, which live in a separate DataStore and can lag the on-disk truth (issue #479):
 *  - [SealState.AUTH]: the passphrase only becomes available after the user authenticates, so the
 *    cache is locked while the session is — INCLUDING the transitional window where `encryptCache`
 *    was already toggled off but the deferred decrypt-to-plaintext (next cold start) hasn't run yet.
 *    Once the file is already plaintext an open no longer needs the passphrase, so a lingering
 *    (orphaned) auth seal alone does not lock it.
 *  - [SealState.MASTER]: the passphrase auto-unwraps without authentication — never locked (e.g.
 *    app-lock was just enabled mid-session and the reseal under the auth key happens only at the
 *    next authentication; stalling sync/push/send until then would be needless).
 *  - [SealState.NONE]: locked only while both settings ask for an auth-armed cache that hasn't been
 *    armed yet (the first-time arm happens at the next authentication).
 *
 * Depends only on DataStore ([SettingsRepository], [DatabaseKeyStore]), in-memory state
 * ([PassphraseSession]) and a raw 16-byte header read of the cache file — never on the Room
 * database — so it is safe to consult *before* touching any DB-backed dependency.
 */
@Singleton
class EncryptedCacheGuard @Inject constructor(
    @ApplicationContext context: Context,
    private val settingsRepository: SettingsRepository,
    private val keyStore: DatabaseKeyStore,
    private val session: PassphraseSession,
) {
    /**
     * The on-disk cache file whose header decides the transitional [SealState.AUTH] case. A
     * [VisibleForTesting] seam (mirroring [DatabaseKeyStore.dataStore]) so tests can point the guard
     * at a fixture file instead of the app's real cache.
     */
    @VisibleForTesting
    internal var dbFile: File = context.getDatabasePath(DatabaseFiles.NAME)

    /**
     * True when the encrypted cache is currently locked, i.e. opening the database would suspend
     * waiting for the user to authenticate. Background work should defer (e.g. `Result.retry()`).
     */
    suspend fun isCacheLocked(): Boolean {
        if (session.isUnlocked()) return false
        val settings = settingsRepository.settings.first()
        return when (keyStore.sealState()) {
            SealState.MASTER -> false
            SealState.NONE -> settings.appLock && settings.encryptCache
            SealState.AUTH -> {
                val locked = settings.encryptCache || DatabaseEncryption.isEncrypted(dbFile)
                if (!settings.encryptCache) {
                    // The transitional window (issue #479): the setting is already off but the
                    // auth seal still exists. Breadcrumb the divergence — booleans only, no PII.
                    AppLog.i(TAG, "auth seal present with encryptCache off (transitional); cacheLocked=$locked")
                }
                locked
            }
        }
    }

    private companion object {
        const val TAG = "LibreMailCacheGuard"
    }
}
