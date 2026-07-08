// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.security

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.data.settings.AppSettings
import org.libremail.data.settings.SettingsRepository
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [KeystoreReportEncryption] delegates the crypto to [KeystoreCrypto] (device-bound, tested in
 * `KeystoreCryptoTest`) and answers [org.libremail.reporting.ReportEncryption.enabled] from an
 * in-memory mirror of the `encryptCache` setting. These pin the delegation and that the mirror tracks
 * the observed setting — including the crash-safe default of "off" before the first value lands.
 */
class KeystoreReportEncryptionTest {

    private val crypto = mockk<KeystoreCrypto>()
    private val settingsRepository = mockk<SettingsRepository>()
    private val encryption = KeystoreReportEncryption(crypto, settingsRepository)

    @Before
    fun setUp() {
        // observeEncryptCacheSetting breadcrumbs the on/off state through AppLog -> android.util.Log,
        // a no-op stub that throws "not mocked" under plain JVM tests. Fully-qualified (a raw
        // android.util.Log import is detekt-forbidden, epic #324).
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `is disabled before the setting has been observed`() {
        assertFalse(encryption.enabled())
    }

    @Test
    fun `encrypt delegates to the keystore crypto`() {
        every { crypto.encrypt("report-json") } returns "cipher"

        assertEquals("cipher", encryption.encrypt("report-json"))
    }

    @Test
    fun `decrypt delegates to the keystore crypto`() {
        every { crypto.decrypt("cipher") } returns "report-json"

        assertEquals("report-json", encryption.decrypt("cipher"))
    }

    @Test
    fun `enabled mirrors the latest encryptCache setting when on`() = runTest {
        every { settingsRepository.settings } returns
            flowOf(AppSettings(encryptCache = false), AppSettings(encryptCache = true))

        encryption.observeEncryptCacheSetting()

        assertTrue(encryption.enabled())
    }

    @Test
    fun `enabled mirrors the latest encryptCache setting when off`() = runTest {
        every { settingsRepository.settings } returns
            flowOf(AppSettings(encryptCache = true), AppSettings(encryptCache = false))

        encryption.observeEncryptCacheSetting()

        assertFalse(encryption.enabled())
    }
}
