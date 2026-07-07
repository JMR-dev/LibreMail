// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import org.libremail.reporting.AppLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AES-256-GCM key in the Android Keystore that seals the SQLCipher passphrase and REQUIRES user
 * authentication (a strong biometric or the device credential) to use — the auth-bound counterpart
 * to [KeystoreCrypto], which stays non-auth-bound so background credential access keeps working.
 *
 * The key is time-bound: a successful `BiometricPrompt` / device-credential unlock authorizes it for
 * [AUTH_VALIDITY_SECONDS], long enough to unwrap the passphrase into [PassphraseSession]. Because the
 * validity window is > 0 no `CryptoObject` is required, which keeps the flow working on API 29 (where
 * a `CryptoObject` cannot be combined with `DEVICE_CREDENTIAL`).
 *
 * [setInvalidatedByBiometricEnrollment] means enrolling a new biometric — and removing the device
 * lock — permanently invalidates the key; [decrypt] then throws [KeyPermanentlyInvalidatedException],
 * which the caller treats as "cache unrecoverable -> clear + re-sync".
 *
 * Reuses the shared [AesGcmKeystoreCipher] plumbing; its delta is the auth-bound [keySpec] and the
 * invalidation handling below. It is created with `generateKeyOnDecrypt = false` on purpose: a
 * missing auth-bound key means it was INVALIDATED, so [decrypt] fails fast (via [onMissingDecryptionKey])
 * rather than silently minting a new key and re-arming the lock against a cache it can never decrypt.
 *
 * DEVICE-ONLY: auth-bound Keystore keys and BiometricPrompt cannot be exercised in JVM unit tests;
 * this class is covered by on-device instrumentation / manual validation only.
 */
@Singleton
class DatabaseKeyCipher @Inject constructor() :
    AesGcmKeystoreCipher(alias = KEY_ALIAS, generateKeyOnDecrypt = false) {

    /**
     * Returns Base64(iv || ciphertext). Requires a valid auth window (call right after unlock).
     * Self-heals a stale, permanently-invalidated key by replacing it and retrying once, so sealing a
     * fresh passphrase after a re-enrollment doesn't fail.
     */
    override fun encrypt(plaintext: String): String = try {
        super.encrypt(plaintext)
    } catch (e: KeyPermanentlyInvalidatedException) {
        AppLog.d(TAG, "replacing invalidated auth-bound key before sealing", e)
        deleteKey()
        super.encrypt(plaintext)
    }

    /**
     * True only when the key exists AND has been permanently invalidated (a new biometric was enrolled
     * or the device lock was removed). A merely-lapsed auth window ([UserNotAuthenticatedException]) is
     * NOT invalidation and returns false, so a routine foreground pass — which calls this before any
     * authentication — never mistakes an unauthenticated key for one that must trigger a cache wipe.
     */
    fun isInvalidated(): Boolean {
        val key = existingKey() ?: return false
        return try {
            initEncryptCipher(key)
            false
        } catch (e: KeyPermanentlyInvalidatedException) {
            AppLog.d(TAG, "auth-bound database key invalidated", e)
            true
        } catch (e: UserNotAuthenticatedException) {
            // Valid key, just outside its time-bound auth window — not invalidated.
            AppLog.d(TAG, "auth-bound key outside its auth window; not invalidated", e)
            false
        } catch (e: Exception) {
            // Never let a validity probe crash the foreground pass; a real decrypt later surfaces any
            // genuine problem. Treat an unknown probe failure as "not invalidated" (don't wipe).
            AppLog.d(TAG, "auth-bound key validity probe failed; treating as valid", e)
            false
        }
    }

    fun hasKey(): Boolean = keyExists()

    /** Deletes the auth-bound key so a fresh one is generated on the next [encrypt]. */
    fun deleteKey(): Unit = deleteKeyEntry()

    /** A missing auth-bound key means it was invalidated; surface that instead of regenerating. */
    override fun onMissingDecryptionKey(): Nothing = error("auth-bound database key is missing")

    override fun keySpec(strongBox: Boolean): KeyGenParameterSpec {
        val builder = keySpecBuilder(strongBox)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                AUTH_VALIDITY_SECONDS,
                AuthenticatorPolicy.keyGenAuthenticators,
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_SECONDS)
        }
        return builder.build()
    }

    private companion object {
        const val KEY_ALIAS = "libremail.dbkey.auth"
        const val AUTH_VALIDITY_SECONDS = 15
        const val TAG = "LibreMailDbKeyAuth"
    }
}
