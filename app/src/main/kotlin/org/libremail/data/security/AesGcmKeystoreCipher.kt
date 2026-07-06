// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import org.libremail.reporting.AppLog
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Shared AES-256-GCM plumbing backed by a non-exportable key in the Android Keystore, parameterized
 * by key [alias] and a missing-key-on-decrypt policy ([generateKeyOnDecrypt]).
 *
 * Encapsulates everything the two Keystore users have in common — the GCM transform and its
 * constants, the alias-scoped key lookup/creation guarded by a lock, `Base64(iv || ciphertext)`
 * framing, and key deletion — so a change to the crypto (StrongBox opt-in, IV handling, error
 * mapping) is made in ONE place instead of being copy-pasted and drifting. Subclasses supply only
 * their delta: the [keySpec] that mints the key (extend [keySpecBuilder] for the common
 * AES-256-GCM base) and, via [generateKeyOnDecrypt], how a decrypt behaves when the alias is absent.
 *
 * That missing-key policy is **deliberately different** between the two users and MUST stay
 * different:
 *  - The non-auth master key ([KeystoreCrypto], `generateKeyOnDecrypt = true`) silently generates a
 *    key on a missing alias — correct for a first-run master key that has nothing sealed yet.
 *  - The auth-bound cache key ([DatabaseKeyCipher], `generateKeyOnDecrypt = false`) fails fast,
 *    because a MISSING auth-bound key means it was INVALIDATED (biometric re-enrollment or lock
 *    removal). Silently regenerating it would defeat the security model — quietly re-arming a lock
 *    against a cache that can no longer be decrypted — so the absence must surface, not self-heal.
 *
 * The key operations touch the Android Keystore, so the two concrete ciphers are exercised on-device;
 * the alias/policy wiring above is unit-tested against this base with fake keys (see the test seams
 * [existingKey], [getOrCreateKey], and [decryptWithKey]).
 */
abstract class AesGcmKeystoreCipher(private val alias: String, private val generateKeyOnDecrypt: Boolean) {

    private val keyLock = Any()

    /** Returns `Base64(iv || ciphertext)`, generating the key under [alias] on first use. */
    open fun encrypt(plaintext: String): String = doEncrypt(getOrCreateKey(), plaintext)

    /**
     * Decrypts a blob produced by [encrypt]. Missing-key handling follows [generateKeyOnDecrypt]
     * (see the class KDoc). An AES-GCM tag mismatch — the ciphertext no longer matches the key, e.g.
     * the alias was cleared and regenerated underneath a still-persisted blob — is surfaced as a
     * clear [GeneralSecurityException] instead of an opaque [AEADBadTagException]. A key-invalidation
     * failure ([android.security.keystore.KeyPermanentlyInvalidatedException]) is thrown from
     * `Cipher.init` and propagates unwrapped, so callers can still classify it.
     */
    fun decrypt(encoded: String): String {
        val key = decryptionKey()
        return try {
            decryptWithKey(key, encoded)
        } catch (e: AEADBadTagException) {
            throw GeneralSecurityException(
                "AES-GCM authentication failed for Keystore alias '$alias': the ciphertext no longer " +
                    "matches the current key (the key was cleared and regenerated, or the data is corrupt)",
                e,
            )
        }
    }

    /** Resolves the key a decrypt should use, applying the [generateKeyOnDecrypt] policy. */
    private fun decryptionKey(): SecretKey =
        if (generateKeyOnDecrypt) getOrCreateKey() else existingKey() ?: onMissingDecryptionKey()

    /**
     * Invoked when a decrypt finds no key and the policy forbids minting one. The default fails with
     * a generic message; auth-bound subclasses override it to explain that the absence means the key
     * was invalidated.
     */
    protected open fun onMissingDecryptionKey(): Nothing = error("Keystore key for alias '$alias' is missing")

    private fun doEncrypt(key: SecretKey, plaintext: String): String {
        val cipher = initEncryptCipher(key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    }

    /** Test seam separating the Keystore-backed cipher call from the decrypt policy/error mapping. */
    protected open fun decryptWithKey(key: SecretKey, encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = bytes.copyOfRange(0, IV_LENGTH)
        val ciphertext = bytes.copyOfRange(IV_LENGTH, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    /**
     * Initializes an encrypt-mode [Cipher] with [key]. Shared by [doEncrypt] and reused by auth-bound
     * subclasses to probe whether a key is still usable (a bare `init` throws if it was invalidated).
     */
    protected fun initEncryptCipher(key: SecretKey): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }

    /** True when a key exists under [alias]. */
    protected fun keyExists(): Boolean = existingKey() != null

    /** Deletes the key so a fresh one is generated on the next [encrypt]. */
    protected fun deleteKeyEntry(): Unit = synchronized(keyLock) {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(alias)
    }

    protected open fun existingKey(): SecretKey? = synchronized(keyLock) {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    // Synchronized so two concurrent first-run encrypts can't both generate a key under the same
    // alias — the second would overwrite the first, leaving the first secret undecryptable.
    protected open fun getOrCreateKey(): SecretKey = synchronized(keyLock) {
        existingKey() ?: generateKeyWithStrongBoxFallback()
    }

    /**
     * Mint the key, preferring the hardware **StrongBox** secure element (a dedicated tamper-resistant
     * chip) so the non-exportable key is bound to the strongest keystore available. Devices without
     * StrongBox report [StrongBoxUnavailableException] at generation time; we then regenerate a
     * TEE-backed key so key creation still succeeds on every device. Both the master ([KeystoreCrypto])
     * and auth-bound ([DatabaseKeyCipher]) keys inherit this through the shared base.
     */
    private fun generateKeyWithStrongBoxFallback(): SecretKey = try {
        generateKey(strongBox = true)
    } catch (e: StrongBoxUnavailableException) {
        // Expected on devices with no StrongBox — not an error. PII-free (a device-capability fact).
        AppLog.i(TAG, "StrongBox unavailable for Keystore alias '$alias'; using a TEE-backed key: ${e.message}")
        generateKey(strongBox = false)
    }

    /**
     * Test seam over the raw Android Keystore key generation for a given [strongBox] preference. The
     * real [KeyGenerator] is device-only, so JVM unit tests override this to exercise the StrongBox
     * fallback in [generateKeyWithStrongBoxFallback] without a Keystore.
     */
    protected open fun generateKey(strongBox: Boolean): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(keySpec(strongBox))
        return generator.generateKey()
    }

    /** The alias-bound [KeyGenParameterSpec] for this key; subclasses extend [keySpecBuilder]. */
    protected abstract fun keySpec(strongBox: Boolean): KeyGenParameterSpec

    /** The common AES-256-GCM builder (encrypt + decrypt, GCM, no padding, 256-bit) to extend. */
    protected fun keySpecBuilder(strongBox: Boolean): KeyGenParameterSpec.Builder = KeyGenParameterSpec.Builder(
        alias,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
    )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(AES_KEY_SIZE_BITS)
        .apply {
            // Bind the key to the StrongBox secure element when requested and supported (API 28+; minSdk
            // is 29, so the guard is defensive). If the device has no StrongBox, generateKey() catches
            // StrongBoxUnavailableException and retries with strongBox = false for a TEE-backed key.
            if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true)
        }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
        const val AES_KEY_SIZE_BITS = 256
        const val TAG = "AesGcmKeystoreCipher"
    }
}
