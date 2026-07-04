// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.security

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.libremail.data.settings.AppSettings
import org.libremail.data.settings.SettingsRepository
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [EncryptedCacheGuard.isCacheLocked] is the pure truth table `appLock && encryptCache && !unlocked`:
 * only when app-lock AND the encrypted cache are both on AND the passphrase session has not been
 * unlocked would opening the Room DB block on authentication, so background work must defer. Every
 * other combination is safe to proceed. Both collaborators are DataStore/in-memory only, so this is a
 * clean JVM test.
 */
class EncryptedCacheGuardTest {

    private val settingsRepository = mockk<SettingsRepository>()
    private val session = mockk<PassphraseSession>()

    private suspend fun cacheLocked(appLock: Boolean, encryptCache: Boolean, unlocked: Boolean): Boolean {
        every { settingsRepository.settings } returns
            flowOf(AppSettings(appLock = appLock, encryptCache = encryptCache))
        every { session.isUnlocked() } returns unlocked
        return EncryptedCacheGuard(settingsRepository, session).isCacheLocked()
    }

    @Test
    fun `locked only when app-lock and encrypted cache are on and the session is not unlocked`() = runTest {
        assertTrue(cacheLocked(appLock = true, encryptCache = true, unlocked = false))
    }

    @Test
    fun `not locked once the passphrase session is unlocked`() = runTest {
        assertFalse(cacheLocked(appLock = true, encryptCache = true, unlocked = true))
    }

    @Test
    fun `not locked when the cache is not encrypted`() = runTest {
        assertFalse(cacheLocked(appLock = true, encryptCache = false, unlocked = false))
    }

    @Test
    fun `not locked when app-lock is off`() = runTest {
        assertFalse(cacheLocked(appLock = false, encryptCache = true, unlocked = false))
    }

    @Test
    fun `not locked when neither app-lock nor encrypted cache is on`() = runTest {
        assertFalse(cacheLocked(appLock = false, encryptCache = false, unlocked = true))
    }
}
