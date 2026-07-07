// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.security

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.libremail.data.settings.SettingsRepository
import org.libremail.reporting.AppLog
import org.libremail.reporting.ReportEncryption
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device [ReportEncryption]: seals a persisted report's JSON with the non-auth Keystore master key
 * ([KeystoreCrypto]) so at-rest report storage honours the opt-in `encryptCache` setting (issue #369).
 * Reuses the vetted AES-256-GCM crypto rather than rolling new; encryption is `Base64(iv || ciphertext)`.
 *
 * The **master** key (not the auth-bound cache key) is deliberate: it is usable without a user-presence
 * prompt, so a crash that occurs while the app is locked can still seal and persist its report — the
 * ticket requires crash reports to survive, encrypted, even then.
 *
 * [enabled] is answered from an in-memory mirror of the `encryptCache` setting, never a live DataStore
 * read: a crash-time [org.libremail.reporting.ReportStore.save] runs synchronously on the crashing
 * thread and must not touch DataStore (#296). [observeEncryptCacheSetting], launched once at startup,
 * keeps that mirror current so a mid-session toggle takes effect on the next report write. The mirror
 * defaults to `false` (plaintext) until the first settings value lands — the same brief unwarmed
 * startup window [org.libremail.reporting.DiagnosticsCollector] accepts for a crash report's settings.
 */
@Singleton
class KeystoreReportEncryption @Inject constructor(
    private val crypto: KeystoreCrypto,
    private val settingsRepository: SettingsRepository,
) : ReportEncryption {

    @Volatile
    private var encryptionEnabled: Boolean = false

    override fun enabled(): Boolean = encryptionEnabled

    override fun encrypt(plaintext: String): String = crypto.encrypt(plaintext)

    override fun decrypt(encoded: String): String = crypto.decrypt(encoded)

    /**
     * Mirrors the `encryptCache` setting into [encryptionEnabled] for the process lifetime. Collects
     * forever, so launch it once from application startup. PII-free — only the on/off state is logged.
     */
    suspend fun observeEncryptCacheSetting() {
        settingsRepository.settings
            .map { it.encryptCache }
            .distinctUntilChanged()
            .collect { enabled ->
                encryptionEnabled = enabled
                AppLog.i(TAG, "Report at-rest encryption is now ${if (enabled) "ON" else "OFF"}")
            }
    }

    private companion object {
        const val TAG = "ReportEncryption"
    }
}
