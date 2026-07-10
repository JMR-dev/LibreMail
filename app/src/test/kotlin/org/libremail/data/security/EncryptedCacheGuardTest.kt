// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.security

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.libremail.data.settings.AppSettings
import org.libremail.data.settings.SettingsRepository
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [EncryptedCacheGuard.isCacheLocked] mirrors [DatabaseKeyStore.resolvePassphrase]'s blocking
 * branches: locked iff opening the Room DB would actually suspend on the user authenticating. The
 * decision is keyed off which seal EXISTS ([DatabaseKeyStore.sealState]) plus — in the transitional
 * [SealState.AUTH]-with-encryptCache-off window (issue #479) — whether the on-disk file is still
 * encrypted. Deriving it from the appLock/encryptCache settings alone answered wrongly in both
 * transitional states: workers parked forever inside `provideDatabase` when the setting was off but
 * the DB still auth-sealed, and sync/push/send stalled needlessly while the seal was still MASTER.
 *
 * The file-header check runs against real temp files (a genuine 16-byte SQLite header vs. junk
 * bytes), so [org.libremail.data.local.DatabaseEncryption.isEncrypted] is exercised for real.
 */
class EncryptedCacheGuardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val settingsRepository = mockk<SettingsRepository>()
    private val keyStore = mockk<DatabaseKeyStore>()
    private val session = mockk<PassphraseSession>()
    private val context = mockk<Context>()

    @Before
    fun setUp() {
        // android.util.Log is a no-op stub that throws "not mocked" in JVM tests; the guard
        // breadcrumbs the transitional divergence through AppLog, which always forwards to it.
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private suspend fun cacheLocked(
        appLock: Boolean = true,
        encryptCache: Boolean = true,
        unlocked: Boolean = false,
        sealState: SealState = SealState.NONE,
        dbFile: File = plaintextDbFile(),
    ): Boolean {
        every { settingsRepository.settings } returns
            flowOf(AppSettings(appLock = appLock, encryptCache = encryptCache))
        every { session.isUnlocked() } returns unlocked
        coEvery { keyStore.sealState() } returns sealState
        every { context.getDatabasePath(any()) } returns dbFile
        return EncryptedCacheGuard(context, settingsRepository, keyStore, session).isCacheLocked()
    }

    /** A file with a genuine plaintext-SQLite 16-byte header (padded past it). */
    private fun plaintextDbFile(): File = tmp.newFile().apply {
        writeBytes("SQLite format 3".toByteArray(Charsets.US_ASCII) + byteArrayOf(0) + ByteArray(32))
    }

    /** A file whose header is NOT the SQLite magic — what a SQLCipher-encrypted cache looks like. */
    private fun encryptedDbFile(): File = tmp.newFile().apply {
        writeBytes(ByteArray(48) { 0x5A })
    }

    // --- SealState.NONE: the original settings-driven rows are preserved -------------------------

    @Test
    fun `no seal - locked only while both settings ask for an auth-armed cache`() = runTest {
        // First-time arm pending: resolvePassphrase(appLock=true) would await the session.
        assertTrue(cacheLocked(appLock = true, encryptCache = true, sealState = SealState.NONE))
    }

    @Test
    fun `no seal - not locked once the passphrase session is unlocked`() = runTest {
        assertFalse(cacheLocked(appLock = true, encryptCache = true, unlocked = true, sealState = SealState.NONE))
    }

    @Test
    fun `no seal - not locked when the cache is not encrypted`() = runTest {
        assertFalse(cacheLocked(appLock = true, encryptCache = false, sealState = SealState.NONE))
    }

    @Test
    fun `no seal - not locked when app-lock is off`() = runTest {
        assertFalse(cacheLocked(appLock = false, encryptCache = true, sealState = SealState.NONE))
    }

    // --- SealState.MASTER: auto-unwraps without authentication — never locked --------------------

    @Test
    fun `master seal - not locked even with both settings on and the session locked`() = runTest {
        // Issue #479 (transitional case B): app-lock/encryptCache just enabled mid-session; the
        // passphrase is still master-sealed until the next authentication reseals it, so the DB
        // opens auth-free — background sync/push/send must NOT stall on the settings pair.
        assertFalse(cacheLocked(appLock = true, encryptCache = true, sealState = SealState.MASTER))
    }

    // --- SealState.AUTH: locked while the session is, including the setting-off window -----------

    @Test
    fun `auth seal - locked while the setting is on and the session is locked`() = runTest {
        assertTrue(cacheLocked(appLock = true, encryptCache = true, sealState = SealState.AUTH))
    }

    @Test
    fun `auth seal - not locked once the session is unlocked`() = runTest {
        assertFalse(cacheLocked(appLock = true, encryptCache = true, unlocked = true, sealState = SealState.AUTH))
    }

    @Test
    fun `auth seal - locked when the setting is off but the cache file is still encrypted`() = runTest {
        // Issue #479 (transitional case A): encryptCache was toggled off but the decrypt runs only
        // at the next cold start. Opening now would suspend in resolvePassphrase on session.await(),
        // so the guard must report locked — the old settings-derived answer said "unlocked" and let
        // workers park forever inside provideDatabase.
        assertTrue(
            cacheLocked(
                appLock = true,
                encryptCache = false,
                sealState = SealState.AUTH,
                dbFile = encryptedDbFile(),
            ),
        )
    }

    @Test
    fun `auth seal - not locked when the setting is off and the cache file is already plaintext`() = runTest {
        // A lingering (orphaned) auth seal after the decrypt-on-disable conversion: the plaintext
        // open needs no passphrase, so background work must not be stalled by the leftover seal.
        assertFalse(
            cacheLocked(
                appLock = true,
                encryptCache = false,
                sealState = SealState.AUTH,
                dbFile = plaintextDbFile(),
            ),
        )
    }

    @Test
    fun `auth seal - locked even when app-lock is off while the file is still encrypted`() = runTest {
        // The desync state the old formula wedged on: app-lock already off but the cache is still
        // auth-sealed and encrypted. resolvePassphrase keys off the seal, so an open WOULD suspend;
        // deferring (retry) is the only safe answer.
        assertTrue(
            cacheLocked(
                appLock = false,
                encryptCache = false,
                sealState = SealState.AUTH,
                dbFile = encryptedDbFile(),
            ),
        )
    }
}
