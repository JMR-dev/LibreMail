// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.reporting

/**
 * At-rest encryption seam for persisted [DebugReport]s (issue #369). When the opt-in cache encryption
 * (`encryptCache`) is ON, [ReportStore] seals a report's storage JSON with this before writing it and
 * unseals it on read; when OFF the JSON is stored plaintext exactly as before.
 *
 * Kept behind an interface so [ReportStore] stays a plain `java.io.File` component that JVM-tests
 * without the Android Keystore. The on-device implementation
 * (`org.libremail.data.security.KeystoreReportEncryption`) wraps the vetted Keystore crypto; [None] is
 * the plaintext default used where encryption isn't wired and by tests that don't exercise it.
 */
interface ReportEncryption {

    /**
     * Whether reports must be encrypted at rest right now — a synchronous, crash-safe mirror of the
     * `encryptCache` setting, never a live DataStore read (a crash-time [ReportStore.save] runs on the
     * crashing thread). When false, [encrypt]/[decrypt] are never called.
     */
    fun enabled(): Boolean

    /** Seals [plaintext] to an opaque at-rest blob. Only invoked when [enabled] is true. */
    fun encrypt(plaintext: String): String

    /** Unseals a blob produced by [encrypt] back to the original plaintext. */
    fun decrypt(encoded: String): String

    /** The plaintext default: encryption disabled, so [encrypt]/[decrypt] are pass-throughs never used. */
    object None : ReportEncryption {
        override fun enabled(): Boolean = false
        override fun encrypt(plaintext: String): String = plaintext
        override fun decrypt(encoded: String): String = encoded
    }
}
