// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.StrongBoxUnavailableException
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.Test
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * JVM coverage for the shared [AesGcmKeystoreCipher] wiring — specifically the deliberately different
 * missing-key-on-decrypt policy the two production ciphers depend on, plus the AES-GCM error mapping.
 * The Keystore-backed operations (real key generation and the GCM cipher) are device-only, so they
 * are replaced here through the [existingKey], [getOrCreateKey], and [decryptWithKey] seams; what is
 * pinned is the base's control flow: which key a decrypt resolves under each `generateKeyOnDecrypt`
 * mode, and how a tag mismatch is surfaced.
 */
class AesGcmKeystoreCipherTest {

    @Test
    fun `generateKeyOnDecrypt true mints a key for a missing alias (master-key behavior)`() {
        val cipher = FakeCipher(generateKeyOnDecrypt = true, storedKey = null)

        assertEquals("plain:blob", cipher.decrypt("blob"))
        assertEquals(1, cipher.generatedKeys, "a missing master alias is generated on decrypt")
    }

    @Test
    fun `generateKeyOnDecrypt true reuses an existing key without regenerating`() {
        val cipher = FakeCipher(generateKeyOnDecrypt = true, storedKey = newAesKey())

        assertEquals("plain:blob", cipher.decrypt("blob"))
        assertEquals(0, cipher.generatedKeys)
    }

    @Test
    fun `generateKeyOnDecrypt false fails fast for a missing alias (auth-bound behavior)`() {
        val cipher = FakeCipher(generateKeyOnDecrypt = false, storedKey = null)

        val error = assertFailsWith<IllegalStateException> { cipher.decrypt("blob") }
        assertTrue(error.message!!.contains("test.alias"))
        assertEquals(0, cipher.generatedKeys, "an absent auth-bound key must NOT be silently regenerated")
        assertEquals(0, cipher.decryptCalls, "decrypt short-circuits before touching the cipher")
    }

    @Test
    fun `generateKeyOnDecrypt false decrypts with the existing key`() {
        val cipher = FakeCipher(generateKeyOnDecrypt = false, storedKey = newAesKey())

        assertEquals("plain:blob", cipher.decrypt("blob"))
        assertEquals(0, cipher.generatedKeys)
    }

    @Test
    fun `an AES-GCM tag mismatch is remapped to a clear GeneralSecurityException`() {
        val badTag = AEADBadTagException("tag mismatch")
        val cipher = FakeCipher(
            generateKeyOnDecrypt = true,
            storedKey = newAesKey(),
            onDecrypt = { _, _ -> throw badTag },
        )

        val error = assertFailsWith<GeneralSecurityException> { cipher.decrypt("blob") }
        assertSame(badTag, error.cause)
        assertTrue(error.message!!.contains("test.alias"))
    }

    @Test
    fun `key generation falls back to a TEE-backed key when StrongBox is unavailable`() {
        // AppLog.i (breadcrumb on the fallback) forwards to android.util.Log, a no-op stub under plain JVM
        // tests; mock it (fully-qualified — a raw android.util.Log import is detekt-forbidden, epic #324).
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        try {
            val teeKey = newAesKey()
            val cipher = StrongBoxFakeCipher(teeKey)

            val key = cipher.createKey()

            // StrongBox is attempted first; its StrongBoxUnavailableException triggers a single retry with
            // strongBox = false, and that TEE-backed key is returned — so generation succeeds everywhere.
            assertSame(teeKey, key)
            assertEquals(listOf(true, false), cipher.attempts)
        } finally {
            unmockkStatic(android.util.Log::class)
        }
    }

    @Test
    fun `a non-AEAD failure propagates unwrapped so key invalidation still surfaces`() {
        // Only AEADBadTagException is remapped; every other cipher failure — including the
        // KeyPermanentlyInvalidatedException a real init throws on an invalidated key — must propagate
        // unchanged so callers can classify it.
        val boom = IllegalArgumentException("boom")
        val cipher = FakeCipher(
            generateKeyOnDecrypt = false,
            storedKey = newAesKey(),
            onDecrypt = { _, _ -> throw boom },
        )

        assertSame(boom, assertFailsWith<IllegalArgumentException> { cipher.decrypt("blob") })
    }

    /**
     * A JVM-only [AesGcmKeystoreCipher] whose Keystore seams are replaced by in-memory fakes so the
     * base's key-resolution policy and error mapping run without a device. [storedKey] models the key
     * present under the alias (null = absent); [onDecrypt] models the GCM cipher operation.
     */
    private class FakeCipher(
        generateKeyOnDecrypt: Boolean,
        private val storedKey: SecretKey?,
        private val onDecrypt: (SecretKey, String) -> String = { _, encoded -> "plain:$encoded" },
    ) : AesGcmKeystoreCipher(alias = "test.alias", generateKeyOnDecrypt = generateKeyOnDecrypt) {

        var generatedKeys = 0
            private set
        var decryptCalls = 0
            private set

        override fun existingKey(): SecretKey? = storedKey

        override fun getOrCreateKey(): SecretKey = existingKey() ?: newAesKey().also { generatedKeys++ }

        override fun decryptWithKey(key: SecretKey, encoded: String): String {
            decryptCalls++
            return onDecrypt(key, encoded)
        }

        override fun keySpec(strongBox: Boolean): KeyGenParameterSpec =
            error("keySpec is not exercised in the JVM base test")
    }

    /**
     * A JVM-only cipher that exercises the REAL [getOrCreateKey]/StrongBox-fallback control flow (unlike
     * [FakeCipher], which stubs [getOrCreateKey] out). [existingKey] returns null so a key is generated,
     * and the [generateKey] seam simulates the device: a StrongBox attempt fails, the TEE attempt yields
     * [teeKey]. [attempts] records the `strongBox` value of each generation attempt in order.
     */
    private class StrongBoxFakeCipher(private val teeKey: SecretKey) :
        AesGcmKeystoreCipher(alias = "test.alias", generateKeyOnDecrypt = true) {

        val attempts = mutableListOf<Boolean>()

        override fun existingKey(): SecretKey? = null

        override fun generateKey(strongBox: Boolean): SecretKey {
            attempts += strongBox
            // Objenesis-instantiated (no stubbed-constructor call) so it is throwable under the android.jar
            // stub; it is still a StrongBoxUnavailableException, so the production catch clause matches.
            if (strongBox) throw mockk<StrongBoxUnavailableException>(relaxed = true)
            return teeKey
        }

        override fun keySpec(strongBox: Boolean): KeyGenParameterSpec =
            error("keySpec is bypassed because generateKey is overridden")

        /** Invokes the protected [getOrCreateKey] so the StrongBox fallback runs without touching Base64. */
        fun createKey(): SecretKey = getOrCreateKey()
    }

    private companion object {
        const val KEY_BYTES = 32

        fun newAesKey(): SecretKey = SecretKeySpec(ByteArray(KEY_BYTES) { it.toByte() }, "AES")
    }
}
