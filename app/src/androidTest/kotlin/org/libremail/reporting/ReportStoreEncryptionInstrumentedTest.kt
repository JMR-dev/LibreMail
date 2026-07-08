// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.reporting

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.data.security.KeystoreCrypto
import java.io.File

/**
 * On-device proof for issue #369: with at-rest encryption ON, [ReportStore] persists a report as real
 * Android Keystore ciphertext — no report content in plaintext on disk — and reads it back intact;
 * with it OFF the file stays plaintext JSON. This closes the gap between the JVM-tested ReportStore
 * branching (which fakes the cipher) and the device-only [KeystoreCrypto] the branching drives in
 * production, using the same non-auth master key that lets a crash-while-locked report still be sealed.
 */
@RunWith(AndroidJUnit4::class)
class ReportStoreEncryptionInstrumentedTest {

    private val context =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val dir = File(context.cacheDir, "report-encryption-test")

    @Before
    fun setUp() {
        dir.deleteRecursively()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun encryptedReportIsCiphertextOnDiskAndReadsBack() {
        val store = store(enabled = true)

        store.save(report("enc"))

        val raw = File(dir, "enc.json").readText()
        // The distinctive plaintext token must NOT be on disk — the report is Keystore-sealed at rest.
        assertFalse("report content must not be persisted in plaintext", raw.contains(SENTINEL))
        assertFalse("a sealed report is not plaintext JSON", raw.startsWith("{"))
        // A fresh store over the same directory (same master key) unseals and reads it back intact.
        assertEquals(SENTINEL, store(enabled = true).find("enc")?.logs?.single())
    }

    @Test
    fun plaintextReportWhenEncryptionOff() {
        val store = store(enabled = false)

        store.save(report("plain"))

        val raw = File(dir, "plain.json").readText()
        assertTrue("with encryption off the report stays plaintext JSON", raw.contains(SENTINEL))
        assertTrue(raw.startsWith("{"))
    }

    private fun store(enabled: Boolean): ReportStore {
        val crypto = KeystoreCrypto()
        val encryption = object : ReportEncryption {
            override fun enabled(): Boolean = enabled
            override fun encrypt(plaintext: String): String = crypto.encrypt(plaintext)
            override fun decrypt(encoded: String): String = crypto.decrypt(encoded)
        }
        return ReportStore(dir, CoroutineScope(Dispatchers.Unconfined), encryption)
    }

    private fun report(id: String) = DebugReport(
        id = id,
        createdAtMillis = 1_000L,
        kind = ReportKind.CRASH,
        appVersionName = "1.0",
        appVersionCode = 1L,
        androidRelease = "14",
        androidSdkInt = 34,
        deviceManufacturer = "Test",
        deviceModel = "Model",
        stackTrace = SENTINEL,
        settings = emptyMap(),
        logs = listOf(SENTINEL),
    )

    private companion object {
        const val SENTINEL = "SENTINEL-PLAINTEXT-TOKEN-369"
    }
}
