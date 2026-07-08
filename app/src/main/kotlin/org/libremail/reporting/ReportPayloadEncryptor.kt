// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.reporting

import org.json.JSONObject
import java.security.KeyFactory
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * Seals a debug-report payload so that only the maintainer can read it, BEFORE it leaves the device
 * (issue #34). This is end-to-end encryption for the report: the ingest Worker (#34) and the R2 bucket
 * it writes to only ever see opaque ciphertext, so "stored objects are not readable without the
 * documented key" holds even against the transport and storage tiers, not just at rest.
 *
 * The device holds only a **public** key, so nothing secret ships in the (F-Droid-buildable) APK; the
 * matching private key is held by the maintainer, off-device. Contrast the on-device [ReportEncryption]
 * (at-rest, symmetric, Android-Keystore key that can never leave the phone): that key could never
 * decrypt a report a remote reviewer must read, which is exactly why this path is asymmetric.
 */
interface ReportPayloadEncryptor {

    /** Whether a usable recipient key is configured. When false, callers MUST NOT upload (fail closed). */
    fun isConfigured(): Boolean

    /** Seals UTF-8 [plaintext] into the self-describing JSON envelope. Only valid when [isConfigured]. */
    fun encrypt(plaintext: String): String

    /** The default when no `DEBUG_REPORT_PUBLIC_KEY` is configured: never encrypts, never uploads. */
    object Disabled : ReportPayloadEncryptor {
        override fun isConfigured(): Boolean = false
        override fun encrypt(plaintext: String): String =
            error("No debug-report public key configured; refusing to produce an unencrypted payload")
    }
}

/**
 * Hybrid (envelope) encryption to a recipient RSA public key: a fresh random AES-256-GCM content key
 * encrypts the payload, and that content key is wrapped with RSA-OAEP (SHA-256 / MGF1-SHA-256). Only
 * JCA primitives are used — no third-party crypto and no Google/GMS dependency, so it builds on pure
 * FOSS toolchains. The output is a compact JSON envelope:
 *
 * ```json
 * { "v": 1, "alg": "RSA-OAEP-SHA256+A256GCM", "ek": "<b64 wrapped key>",
 *   "iv": "<b64 12-byte GCM IV>", "ct": "<b64 ciphertext||tag>" }
 * ```
 *
 * Uses [java.util.Base64] (not `android.util.Base64`) so the class is exercisable in JVM unit tests,
 * where the `android.util` shim is a no-op stub.
 */
class HybridReportPayloadEncryptor(
    private val recipient: PublicKey,
    private val random: SecureRandom = SecureRandom(),
) : ReportPayloadEncryptor {

    override fun isConfigured(): Boolean = true

    override fun encrypt(plaintext: String): String {
        val contentKey = KeyGenerator.getInstance("AES").apply { init(AES_KEY_BITS, random) }.generateKey()
        val iv = ByteArray(GCM_IV_BYTES).also(random::nextBytes)
        val gcm = Cipher.getInstance(AES_TRANSFORM).apply {
            init(Cipher.ENCRYPT_MODE, contentKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        val ciphertext = gcm.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val rsa = Cipher.getInstance(RSA_TRANSFORM).apply {
            init(Cipher.ENCRYPT_MODE, recipient, oaepParams())
        }
        val wrappedKey = rsa.doFinal(contentKey.encoded)
        return JSONObject()
            .put("v", ENVELOPE_VERSION)
            .put("alg", ALG)
            .put("ek", b64(wrappedKey))
            .put("iv", b64(iv))
            .put("ct", b64(ciphertext))
            .toString()
    }

    companion object {
        const val ENVELOPE_VERSION = 1
        const val ALG = "RSA-OAEP-SHA256+A256GCM"
        private const val AES_TRANSFORM = "AES/GCM/NoPadding"
        private const val RSA_TRANSFORM = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
        private const val AES_KEY_BITS = 256
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val PEM_HEADER = "-----BEGIN PUBLIC KEY-----"
        private const val PEM_FOOTER = "-----END PUBLIC KEY-----"

        /**
         * The OAEP parameters used for the RSA key wrap: SHA-256 for BOTH the digest and the MGF1 mask,
         * pinned explicitly to avoid the classic provider default of a SHA-1 MGF1 under a SHA-256 digest.
         * Decrypters must init with the identical spec.
         */
        fun oaepParams(): OAEPParameterSpec =
            OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)

        /**
         * Parses a Base64-encoded X.509 SubjectPublicKeyInfo (SPKI) RSA public key, tolerating PEM
         * armor and embedded whitespace. Throws (`IllegalArgumentException` /
         * `java.security.spec.InvalidKeySpecException`) on malformed input; the DI provider treats any
         * such failure as "not configured".
         */
        fun parsePublicKey(encoded: String): PublicKey {
            val der = Base64.getDecoder().decode(normalize(encoded))
            return KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
        }

        private fun normalize(encoded: String): String =
            encoded.replace(PEM_HEADER, "").replace(PEM_FOOTER, "").filterNot(Char::isWhitespace)

        private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    }
}
