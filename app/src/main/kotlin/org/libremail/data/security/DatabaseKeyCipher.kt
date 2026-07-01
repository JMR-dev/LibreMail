// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
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
 * DEVICE-ONLY: auth-bound Keystore keys and BiometricPrompt cannot be exercised in JVM unit tests;
 * this class is covered by on-device instrumentation / manual validation only.
 */
@Singleton
class DatabaseKeyCipher @Inject constructor() {

    private val keyLock = Any()

    /**
     * Returns Base64(iv || ciphertext). Requires a valid auth window (call right after unlock).
     * Self-heals a stale, permanently-invalidated key by replacing it and retrying once, so sealing a
     * fresh passphrase after a re-enrollment doesn't fail.
     */
    fun encrypt(plaintext: String): String = try {
        doEncrypt(getOrCreateKey(), plaintext)
    } catch (e: KeyPermanentlyInvalidatedException) {
        Log.d(TAG, "replacing invalidated auth-bound key before sealing", e)
        deleteKey()
        doEncrypt(getOrCreateKey(), plaintext)
    }

    private fun doEncrypt(key: SecretKey, plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    }

    /**
     * Decrypts a blob produced by [encrypt]. Requires a valid auth window. Throws
     * [KeyPermanentlyInvalidatedException] if the key was invalidated by re-enrollment / lock removal.
     */
    fun decrypt(encoded: String): String {
        val key = existingKey() ?: error("auth-bound database key is missing")
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = bytes.copyOfRange(0, IV_LENGTH)
        val ciphertext = bytes.copyOfRange(IV_LENGTH, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    /**
     * True when the key exists but has been permanently invalidated (a new biometric was enrolled or
     * the device lock was removed). Detected by attempting to initialize a cipher, which fails fast
     * without needing an auth window.
     */
    fun isInvalidated(): Boolean {
        val key = existingKey() ?: return false
        return try {
            Cipher.getInstance(TRANSFORMATION).init(Cipher.ENCRYPT_MODE, key)
            false
        } catch (e: KeyPermanentlyInvalidatedException) {
            Log.d(TAG, "auth-bound database key invalidated", e)
            true
        }
    }

    fun hasKey(): Boolean = existingKey() != null

    /** Deletes the auth-bound key so a fresh one is generated on the next [encrypt]. */
    fun deleteKey(): Unit = synchronized(keyLock) {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
    }

    private fun existingKey(): SecretKey? = synchronized(keyLock) {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    private fun getOrCreateKey(): SecretKey = synchronized(keyLock) {
        existingKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(buildSpec())
        generator.generateKey()
    }

    private fun buildSpec(): KeyGenParameterSpec {
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(AES_KEY_SIZE_BITS)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                AUTH_VALIDITY_SECONDS,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_SECONDS)
        }
        return builder.build()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "libremail.dbkey.auth"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
        const val AES_KEY_SIZE_BITS = 256
        const val AUTH_VALIDITY_SECONDS = 15
        const val TAG = "LibreMailDbKeyAuth"
    }
}
