// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local

import android.content.Context
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.data.security.DatabaseKeyStore
import org.libremail.data.settings.AppSettings
import org.libremail.data.settings.SettingsRepository
import java.io.File
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * [DatabaseProvisioner] holds the one-time startup sequence that `DatabaseModule.provideDatabase` used
 * to run inline while Hilt constructed the database (a DataStore read, a Keystore op, a possible
 * SQLCipher re-key conversion, and the issue-#111 account migrator) — synchronously on whichever thread
 * injected it, possibly the main thread. These tests pin down what moving that work behind
 * [DatabaseProvisioner.prepareCache] must preserve (issue #93): the same ordering (wipe -> migrate ->
 * encryption gate), the same branch behaviour, single-run memoization, and that the blocking work runs
 * on the injected IO dispatcher rather than the caller's thread.
 *
 * The native/file collaborators ([DatabaseEncryption], [DatabaseFiles]) and the suspend collaborators
 * are all mocked, so this exercises the orchestration without a device.
 */
class DatabaseProvisionerTest {

    private val context = mockk<Context>()
    private val keyStore = mockk<DatabaseKeyStore>()
    private val settingsRepository = mockk<SettingsRepository>()
    private val accountDataMigrator = mockk<AccountDataMigrator>()
    private lateinit var ioDispatcher: ExecutorCoroutineDispatcher

    @Before
    fun setUp() {
        ioDispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, IO_THREAD_NAME) }
            .asCoroutineDispatcher()
        mockkObject(DatabaseEncryption)
        mockkObject(DatabaseFiles)
        // `android.util.Log` is a no-op stub under plain JVM unit tests; the #359 degrade path breadcrumbs
        // through AppLog.w, so statically mock Log (fully-qualified — a raw android.util.Log import is
        // detekt-forbidden, epic #324) so it does not throw "not mocked".
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>(), any()) } returns 0

        every { context.getDatabasePath(any()) } returns File("libremail.db")
        every { DatabaseFiles.clear(any()) } just Runs
        every { DatabaseEncryption.isEncrypted(any()) } returns false
        every { DatabaseEncryption.ensureEncrypted(any(), any()) } just Runs
        every { DatabaseEncryption.ensurePlaintext(any(), any()) } just Runs
        every { DatabaseEncryption.ensureNativeLibraryLoaded() } just Runs
        every { settingsRepository.settings } returns flowOf(AppSettings())

        coEvery { keyStore.isClearPending() } returns false
        coEvery { keyStore.resetSealedPassphrase() } just Runs
        coEvery { keyStore.clearClearPending() } just Runs
        coEvery { keyStore.resolvePassphrase(any()) } returns PASSPHRASE
        coEvery { accountDataMigrator.migrateIfNeeded() } just Runs
    }

    @After
    fun tearDown() {
        ioDispatcher.close()
        unmockkAll()
    }

    private fun provisioner() =
        DatabaseProvisioner(context, keyStore, settingsRepository, accountDataMigrator, ioDispatcher)

    @Test
    fun `an encrypted cache is converted and reports the SQLCipher passphrase`() = runTest {
        every { settingsRepository.settings } returns flowOf(AppSettings(encryptCache = true, appLock = false))

        val mode = provisioner().prepareCache()

        assertEquals(CacheOpenMode.Encrypted(PASSPHRASE), mode)
        coVerify(exactly = 1) { keyStore.resolvePassphrase(false) }
        verify(exactly = 1) { DatabaseEncryption.ensureEncrypted(any(), PASSPHRASE) }
        verify(exactly = 0) { DatabaseEncryption.ensurePlaintext(any(), any()) }
        // Regression (crash-on-launch after upgrade): the encrypted open path MUST load SQLCipher's
        // native library itself. ensureEncrypted no-ops when the cache is already encrypted, so if the
        // load only rode on that conversion, Room's keyed open would hit nativeOpen with no .so loaded
        // and throw UnsatisfiedLinkError on every cold start.
        verify(exactly = 1) { DatabaseEncryption.ensureNativeLibraryLoaded() }
    }

    @Test
    fun `a native-library load failure degrades an encrypted cache to a plaintext open`() = runTest {
        every { settingsRepository.settings } returns flowOf(AppSettings(encryptCache = true, appLock = false))
        coEvery { settingsRepository.setEncryptCache(any()) } just Runs
        // Issue #359: on a 16 KB-page device the SQLCipher `.so` fails to load, throwing UnsatisfiedLinkError
        // (a LinkageError) at the keyed open. The provisioner must degrade, not propagate the crash.
        val nativeLoadFailure = UnsatisfiedLinkError("dlopen failed: libsqlcipher.so is not 16 KB aligned")
        every { DatabaseEncryption.ensureNativeLibraryLoaded() } throws nativeLoadFailure

        val mode = provisioner().prepareCache()

        // Degrades to a working plaintext open and turns the setting off so the next start does not
        // re-attempt the same failing conversion (matching the crash-report forensics: encryptCache=false).
        assertEquals(CacheOpenMode.Plaintext, mode)
        coVerify(exactly = 1) { settingsRepository.setEncryptCache(false) }
        // The on-disk cache is plaintext here (isEncrypted stubbed false), so there is nothing to wipe.
        verify(exactly = 0) { DatabaseFiles.clear(any()) }
    }

    @Test
    fun `a native-library load failure wipes an already-encrypted cache and resets its seals`() = runTest {
        every { settingsRepository.settings } returns flowOf(AppSettings(encryptCache = true, appLock = false))
        coEvery { settingsRepository.setEncryptCache(any()) } just Runs
        every { DatabaseEncryption.isEncrypted(any()) } returns true
        val nativeLoadFailure = UnsatisfiedLinkError("dlopen failed: libsqlcipher.so is not 16 KB aligned")
        every { DatabaseEncryption.ensureEncrypted(any(), any()) } throws nativeLoadFailure

        val mode = provisioner().prepareCache()

        assertEquals(CacheOpenMode.Plaintext, mode)
        coVerify(exactly = 1) { settingsRepository.setEncryptCache(false) }
        // Ciphertext the plaintext framework opener cannot parse is cleared, and its stale seal reset.
        verify(exactly = 1) { DatabaseFiles.clear(any()) }
        coVerify(exactly = 1) { keyStore.resetSealedPassphrase() }
    }

    @Test
    fun `prepareCache suspends on the auth-bound passphrase until it resolves`() = runTest {
        every { settingsRepository.settings } returns flowOf(AppSettings(encryptCache = true, appLock = true))
        val enteredResolve = CompletableDeferred<Unit>()
        val releasePassphrase = CompletableDeferred<String>()
        // Model the auth-sealed key: resolvePassphrase parks until the user authenticates. Keeping this
        // await off a background thread is exactly why the pre-auth DB entry points gate on
        // EncryptedCacheGuard — this pins that prepareCache really does block on it.
        coEvery { keyStore.resolvePassphrase(true) } coAnswers {
            enteredResolve.complete(Unit)
            releasePassphrase.await()
        }

        val prepared = async { provisioner().prepareCache() }
        enteredResolve.await() // the startup sequence has reached the passphrase await

        assertFalse(prepared.isCompleted, "prepareCache must not complete while the passphrase is unresolved")

        releasePassphrase.complete(PASSPHRASE)
        assertEquals(CacheOpenMode.Encrypted(PASSPHRASE), prepared.await())
    }

    @Test
    fun `a pending clear wipes and resets the seals before the migrator runs`() = runTest {
        coEvery { keyStore.isClearPending() } returns true

        provisioner().prepareCache()

        // Crash-safe order preserved from the old provideDatabase: wipe + reset the seals, THEN clear the
        // pending flag, THEN migrate — never touching the cache file after an open connection exists.
        coVerifyOrder {
            keyStore.isClearPending()
            DatabaseFiles.clear(any())
            keyStore.resetSealedPassphrase()
            keyStore.clearClearPending()
            accountDataMigrator.migrateIfNeeded()
        }
    }

    @Test
    fun `the account migrator runs before the cache encryption gate`() = runTest {
        every { settingsRepository.settings } returns flowOf(AppSettings(encryptCache = true))

        provisioner().prepareCache()

        // The #111 migrate-before-open guarantee: the account tables are copied out BEFORE the cache is
        // touched (here, before its passphrase is resolved and it is re-keyed).
        coVerifyOrder {
            accountDataMigrator.migrateIfNeeded()
            keyStore.resolvePassphrase(any())
            DatabaseEncryption.ensureEncrypted(any(), any())
        }
    }

    @Test
    fun `an encrypted file with encryption turned off is decrypted to plaintext`() = runTest {
        every { settingsRepository.settings } returns flowOf(AppSettings(encryptCache = false))
        every { DatabaseEncryption.isEncrypted(any()) } returns true

        val mode = provisioner().prepareCache()

        assertEquals(CacheOpenMode.Plaintext, mode)
        verify(exactly = 1) { DatabaseEncryption.ensurePlaintext(any(), PASSPHRASE) }
        verify(exactly = 0) { DatabaseEncryption.ensureEncrypted(any(), any()) }
    }

    @Test
    fun `a plaintext cache with encryption off touches neither the passphrase nor a conversion`() = runTest {
        val mode = provisioner().prepareCache() // defaults: encryptCache = false, file not encrypted

        assertEquals(CacheOpenMode.Plaintext, mode)
        coVerify(exactly = 0) { keyStore.resolvePassphrase(any()) }
        verify(exactly = 0) { DatabaseEncryption.ensureEncrypted(any(), any()) }
        verify(exactly = 0) { DatabaseEncryption.ensurePlaintext(any(), any()) }
        // A plaintext cache opens with the framework helper, never SQLCipher, so it must not touch the
        // native library — the counterpart to the encrypted path's mandatory load above.
        verify(exactly = 0) { DatabaseEncryption.ensureNativeLibraryLoaded() }
    }

    @Test
    fun `the startup sequence runs once and is memoized across calls`() = runTest {
        val provisioner = provisioner()

        repeat(3) { provisioner.prepareCache() }

        coVerify(exactly = 1) { keyStore.isClearPending() }
        coVerify(exactly = 1) { accountDataMigrator.migrateIfNeeded() }
        verify(exactly = 1) { settingsRepository.settings }
    }

    @Test
    fun `concurrent first opens collapse to a single run`() = runTest {
        val provisioner = provisioner()

        // Both databases opening at once each gate on prepareCache; the mutex must collapse them to one
        // run of the migrator (opening the cache twice would be a correctness bug).
        val first = async { provisioner.prepareCache() }
        val second = async { provisioner.prepareCache() }
        awaitAll(first, second)

        coVerify(exactly = 1) { accountDataMigrator.migrateIfNeeded() }
    }

    @Test
    fun `the blocking sequence runs on the injected io dispatcher, not the caller`() = runTest {
        val migratorThread = CompletableDeferred<String>()
        coEvery { accountDataMigrator.migrateIfNeeded() } coAnswers {
            migratorThread.complete(Thread.currentThread().name)
        }

        provisioner().prepareCache()

        assertEquals(
            IO_THREAD_NAME,
            migratorThread.await(),
            "the startup work must run on the injected IO dispatcher, off the calling thread",
        )
    }

    private companion object {
        // 64 hex chars == a 32-byte SQLCipher passphrase, matching DatabaseKeyStore's format.
        const val PASSPHRASE = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val IO_THREAD_NAME = "test-db-io-dispatcher"
    }
}
