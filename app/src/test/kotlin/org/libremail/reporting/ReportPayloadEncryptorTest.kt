// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.reporting

import org.json.JSONObject
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [HybridReportPayloadEncryptor] round-trips: the test generates an RSA key pair, encrypts with the
 * PUBLIC half exactly as the app would, then decrypts with the PRIVATE half — the maintainer's role.
 * It also pins the privacy-critical properties: the plaintext never appears in the envelope, each seal
 * is unique (fresh content key + IV), and tampering fails the GCM tag.
 */
class ReportPayloadEncryptorTest {

    private val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val encryptor = HybridReportPayloadEncryptor(keyPair.public)

    @Test
    fun `encrypt then decrypt recovers the original payload`() {
        val plaintext = """{"id":"rid","userComment":"the sync keeps failing"}"""

        val recovered = decrypt(encryptor.encrypt(plaintext), keyPair.private)

        assertEquals(plaintext, recovered)
    }

    @Test
    fun `the envelope is well-formed and never contains the plaintext`() {
        val plaintext = """{"marker":"TOP-SECRET-MARKER-42"}"""

        val envelope = JSONObject(encryptor.encrypt(plaintext))

        assertEquals(HybridReportPayloadEncryptor.ENVELOPE_VERSION, envelope.getInt("v"))
        assertEquals(HybridReportPayloadEncryptor.ALG, envelope.getString("alg"))
        assertTrue(envelope.getString("ek").isNotEmpty())
        assertTrue(envelope.getString("iv").isNotEmpty())
        assertTrue(envelope.getString("ct").isNotEmpty())
        assertFalse(envelope.toString().contains("TOP-SECRET-MARKER-42"))
    }

    @Test
    fun `each seal of the same plaintext is unique`() {
        val plaintext = "identical input"

        val first = JSONObject(encryptor.encrypt(plaintext))
        val second = JSONObject(encryptor.encrypt(plaintext))

        // Fresh random content key + IV each time -> different wrapped key, IV, and ciphertext.
        assertNotEquals(first.getString("ct"), second.getString("ct"))
        assertNotEquals(first.getString("iv"), second.getString("iv"))
        assertNotEquals(first.getString("ek"), second.getString("ek"))
    }

    @Test
    fun `a tampered ciphertext fails the GCM authentication tag`() {
        val envelope = JSONObject(encryptor.encrypt("authentic payload"))
        val ct = Base64.getDecoder().decode(envelope.getString("ct"))
        ct[0] = (ct[0].toInt() xor 0x01).toByte() // flip one bit
        envelope.put("ct", Base64.getEncoder().encodeToString(ct))

        assertFailsWith<AEADBadTagException> { decrypt(envelope.toString(), keyPair.private) }
    }

    @Test
    fun `isConfigured is true for a real key and false for Disabled`() {
        assertTrue(encryptor.isConfigured())
        assertFalse(ReportPayloadEncryptor.Disabled.isConfigured())
    }

    @Test
    fun `Disabled refuses to encrypt`() {
        assertFailsWith<IllegalStateException> { ReportPayloadEncryptor.Disabled.encrypt("anything") }
    }

    @Test
    fun `parsePublicKey round-trips a Base64 SPKI key`() {
        val spkiBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)

        val parsed = HybridReportPayloadEncryptor.parsePublicKey(spkiBase64)

        assertEquals(keyPair.public, parsed)
    }

    @Test
    fun `parsePublicKey tolerates PEM armor and whitespace`() {
        val body = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        val pem = "-----BEGIN PUBLIC KEY-----\n" + body.chunked(64).joinToString("\n") + "\n-----END PUBLIC KEY-----\n"

        assertEquals(keyPair.public, HybridReportPayloadEncryptor.parsePublicKey(pem))
    }

    @Test
    fun `parsePublicKey rejects malformed input`() {
        assertFailsWith<IllegalArgumentException> { HybridReportPayloadEncryptor.parsePublicKey("not base64 !!!") }
    }

    /** The maintainer-side decrypt: unwrap the content key with the RSA private key, then open the GCM box. */
    private fun decrypt(envelopeJson: String, privateKey: PrivateKey): String {
        val envelope = JSONObject(envelopeJson)
        val wrappedKey = Base64.getDecoder().decode(envelope.getString("ek"))
        val iv = Base64.getDecoder().decode(envelope.getString("iv"))
        val ciphertext = Base64.getDecoder().decode(envelope.getString("ct"))
        val rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").apply {
            init(Cipher.DECRYPT_MODE, privateKey, HybridReportPayloadEncryptor.oaepParams())
        }
        val contentKey = SecretKeySpec(rsa.doFinal(wrappedKey), "AES")
        val gcm = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, contentKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        return String(gcm.doFinal(ciphertext), Charsets.UTF_8)
    }

    private companion object {
        const val GCM_TAG_BITS = 128
    }
}
