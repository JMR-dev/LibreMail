// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.reporting

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * At-rest encryption of persisted reports (issue #369). Uses a reversible fake [ReportEncryption]
 * (Base64, so the on-disk body genuinely scrambles the report content the way real AES-GCM does) — the
 * Keystore round-trip itself is [org.libremail.data.security.KeystoreCrypto]'s device-bound concern.
 * These pin [ReportStore]'s own branching: seal-on-write when enabled, plaintext when off, both formats
 * readable together, and the fail-closed guarantee that a sealing failure never leaves a plaintext
 * report on disk.
 */
class ReportStoreEncryptionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        // ReportStore's fail-closed / skip-on-failure paths breadcrumb through AppLog -> android.util.Log,
        // a no-op stub that throws "not mocked" under plain JVM tests. Fully-qualified (a raw
        // android.util.Log import is detekt-forbidden, epic #324).
        mockkStatic(android.util.Log::class)
        every { android.util.Log.e(any<String>(), any<String>(), any()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>(), any()) } returns 0
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `encrypts the report at rest when enabled and reads it back`() {
        val store = store(FakeEncryption(enabled = true))

        store.save(report("enc"))

        val raw = File(tempFolder.root, "enc.json").readText()
        // Sealed at rest: the distinctive plaintext token is NOT on disk, and the file is not the JSON.
        assertFalse(raw.contains(SENTINEL))
        assertFalse(raw.startsWith("{"))
        // Still fully readable through the store (decrypted on scan).
        assertEquals(SENTINEL, store.find("enc")?.logs?.single())
        // Survives a fresh instance over the same directory (the next launch decrypts and reads it).
        assertEquals("enc", store(FakeEncryption(enabled = true)).find("enc")?.id)
    }

    @Test
    fun `stores plaintext byte-for-byte when disabled`() {
        val store = store(FakeEncryption(enabled = false))

        store.save(report("plain"))

        val raw = File(tempFolder.root, "plain.json").readText()
        // Exactly the historical plaintext storage form — the token is present and it is JSON.
        assertEquals(report("plain").toStorageJson(), raw)
        assertTrue(raw.contains(SENTINEL))
        assertEquals("plain", store.find("plain")?.id)
    }

    @Test
    fun `persists a crash report encrypted at rest`() {
        val store = store(FakeEncryption(enabled = true))

        store.save(report("crash", kind = ReportKind.CRASH))

        val raw = File(tempFolder.root, "crash.json").readText()
        assertFalse(raw.contains(SENTINEL))
        assertEquals(ReportKind.CRASH, store.find("crash")?.kind)
    }

    @Test
    fun `fails closed - a sealing failure never writes a plaintext report`() {
        val store = store(FakeEncryption(enabled = true, encryptor = { error("keystore unavailable") }))

        store.save(report("boom"))

        // Nothing was written: no plaintext leak, and the report is simply absent (crash still propagates).
        assertFalse(File(tempFolder.root, "boom.json").exists())
        assertTrue(store.reports.value.isEmpty())
        assertNull(store.find("boom"))
    }

    @Test
    fun `reads a mix of plaintext and encrypted reports on disk`() {
        // An older plaintext report from before encryption was turned on, written directly.
        File(tempFolder.root, "old.json").writeText(report("old", createdAt = 1L).toStorageJson())
        val store = store(FakeEncryption(enabled = true))

        // A newer report saved after the setting was turned on is sealed.
        store.save(report("new", createdAt = 2L))

        assertEquals(listOf("new", "old"), store.reports.value.map { it.id })
    }

    @Test
    fun `skips a report that cannot be decrypted`() {
        // Seal a report with a working store, then reopen with one whose decrypt fails (key rotated).
        store(FakeEncryption(enabled = true)).save(report("sealed"))

        val broken = store(FakeEncryption(enabled = true, decryptor = { error("key was cleared") }))

        assertTrue(broken.reports.value.isEmpty())
        assertNull(broken.find("sealed"))
    }

    @Test
    fun `markSurfaced re-seals the report and persists the flag`() {
        val store = store(FakeEncryption(enabled = true))
        store.save(report("a"))

        store.markSurfaced("a")

        val raw = File(tempFolder.root, "a.json").readText()
        assertFalse(raw.contains(SENTINEL))
        assertTrue(store.find("a")!!.surfaced)
        // A fresh instance decrypts and reads it back as surfaced.
        assertTrue(store(FakeEncryption(enabled = true)).find("a")!!.surfaced)
    }

    @Test
    fun `None encryption is a disabled plaintext pass-through`() {
        assertFalse(ReportEncryption.None.enabled())
        assertEquals("x", ReportEncryption.None.encrypt("x"))
        assertEquals("x", ReportEncryption.None.decrypt("x"))
    }

    private fun store(encryption: ReportEncryption) =
        ReportStore(tempFolder.root, CoroutineScope(Dispatchers.Unconfined), encryption)

    private fun report(id: String, createdAt: Long = 1L, kind: ReportKind = ReportKind.MANUAL) = DebugReport(
        id = id,
        createdAtMillis = createdAt,
        kind = kind,
        appVersionName = "0.1.0",
        appVersionCode = 1,
        androidRelease = "14",
        androidSdkInt = 34,
        deviceManufacturer = "Google",
        deviceModel = "Pixel",
        stackTrace = null,
        settings = emptyMap(),
        logs = listOf(SENTINEL),
    )

    /**
     * A reversible stand-in for the Keystore cipher: Base64 genuinely scrambles the report content on
     * disk (so a "not plaintext" assertion is meaningful) while round-tripping. [encryptor]/[decryptor]
     * are overridable to inject failures.
     */
    private class FakeEncryption(
        private val enabled: Boolean,
        private val encryptor: (String) -> String = { Base64.getEncoder().encodeToString(it.toByteArray()) },
        private val decryptor: (String) -> String = { String(Base64.getDecoder().decode(it)) },
    ) : ReportEncryption {
        override fun enabled(): Boolean = enabled
        override fun encrypt(plaintext: String): String = encryptor(plaintext)
        override fun decrypt(encoded: String): String = decryptor(encoded)
    }

    private companion object {
        const val SENTINEL = "SENTINEL-PLAINTEXT-369"
    }
}
