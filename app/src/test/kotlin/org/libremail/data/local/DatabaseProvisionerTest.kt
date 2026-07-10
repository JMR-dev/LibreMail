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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
        every { android.util.Log.i(any(), any()) } returns 0

        every { context.getDatabasePath(any()) } returns File("libremail.db")
        every { DatabaseFiles.clear(any()) } just Runs
        every { DatabaseEncryption.isEncrypted(any()) } returns false
        every { DatabaseEncryption.ensureEncrypted(any(), any()) } just Runs
        every { DatabaseEncryption.ensurePlaintext(any(), any()) } just Runs
        every { DatabaseEncryption.ensureNativeLibraryLoaded() } just Runs
        every { DatabaseEncryption.probeKeyedOpen(any(), any()) } just Runs
        every { settingsRepository.settings } returns flowOf(AppSettings())

        coEvery { keyStore.isClearPending() } returns false
        coEvery { keyStore.resetSealedPassphrase() } just Runs
        coEvery { keyStore.clearClearPending() } just Runs
        coEvery { keyStore.resolvePassphrase(any()) } returns PASSPHRASE
        coEvery { keyStore.hasAuthSealedPassphrase() } returns false
        coEvery { keyStore.sealWithMaster() } just Runs
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
        // Issue #359 gap 2: the encrypted branch also probes a REAL keyed open (nativeOpen) inside the
        // fail-closed handler, so an open-time UnsatisfiedLinkError is caught here rather than escaping
        // Room's later deferred open.
        verify(exactly = 1) { DatabaseEncryption.probeKeyedOpen(any(), PASSPHRASE) }
    }

    @Test
    fun `a native-library load failure fails closed without opening plaintext or touching the setting`() = runTest {
        every { settingsRepository.settings } returns flowOf(AppSettings(encryptCache = true, appLock = false))
        // Issue #359: the SQLCipher `.so` fails to load, throwing UnsatisfiedLinkError (a LinkageError) at
        // the keyed open. The provisioner must FAIL CLOSED — raise a distinct signal, never silently
        // degrade to an unencrypted cache (which would defeat the user's opt-in encryption).
        val nativeLoadFailure = UnsatisfiedLinkError("dlopen failed: libsqlcipher.so is not 16 KB aligned")
        every { DatabaseEncryption.ensureNativeLibraryLoaded() } throws nativeLoadFailure

        val error = assertFailsWith<CacheEncryptionUnavailableException> { provisioner().prepareCache() }

        // The distinct signal preserves the underlying native LinkageError in its cause chain (coroutine
        // stack-trace recovery may re-wrap the exception across withContext, so assert the chain rather
        // than exact instance identity), and NONE of the old fail-open side effects run: the setting is
        // never flipped, nothing is wiped, and no seal is reset.
        assertTrue(
            generateSequence(error.cause) { it.cause }.any { it is LinkageError },
            "the native-load LinkageError must be preserved as the cause",
        )
        coVerify(exactly = 0) { settingsRepository.setEncryptCache(any()) }
        verify(exactly = 0) { DatabaseFiles.clear(any()) }
        coVerify(exactly = 0) { keyStore.resetSealedPassphrase() }
    }

    @Test
    fun `a native-library load failure never wipes an already-encrypted cache`() = runTest {
        every { settingsRepository.settings } returns flowOf(AppSettings(encryptCache = true, appLock = false))
        every { DatabaseEncryption.isEncrypted(any()) } returns true
        every { DatabaseEncryption.ensureEncrypted(any(), any()) } throws
            UnsatisfiedLinkError("dlopen failed: libsqlcipher.so is not 16 KB aligned")

        assertFailsWith<CacheEncryptionUnavailableException> { provisioner().prepareCache() }

        // The ciphertext the plaintext opener can't parse is deliberately PRESERVED (it may become
        // readable again once the library loads on a later launch), the seals stay intact, and the
        // setting is untouched — the opposite of the rejected degrade-and-wipe behaviour.
        verify(exactly = 0) { DatabaseFiles.clear(any()) }
        coVerify(exactly = 0) { keyStore.resetSealedPassphrase() }
        coVerify(exactly = 0) { settingsRepository.setEncryptCache(any()) }
    }

    @Test
    fun `a native-library load failure in the account migrator fails closed`() = runTest {
        // Issue #359 gap 1 (PRIMARY): AccountDataMigrator.copyAccountTables loads SQLCipher and does a
        // keyed openOrCreateDatabase + ATTACH … KEY (a real nativeOpen) even when encryption is OFF, so a
        // LinkageError there must fail closed too. It used to ESCAPE the handler because migrateIfNeeded()
        // ran OUTSIDE the try/catch (crash-loop). Un-mock the `just Runs` default (which hid the gap) and
        // make the migrator throw the exact #359 error — encryption stays at its default OFF here to prove
        // the migrator drags EVERY upgrader through the native library regardless of the setting.
        coEvery { accountDataMigrator.migrateIfNeeded() } throws
            UnsatisfiedLinkError("dlopen failed: libsqlcipher.so is not 16 KB aligned")

        val error = assertFailsWith<CacheEncryptionUnavailableException> { provisioner().prepareCache() }

        assertTrue(
            generateSequence(error.cause) { it.cause }.any { it is LinkageError },
            "the native-load LinkageError must be preserved as the cause, not surface as a raw LinkageError",
        )
        // Fail CLOSED, not open: nothing wiped, no seal reset, the setting untouched.
        verify(exactly = 0) { DatabaseFiles.clear(any()) }
        coVerify(exactly = 0) { keyStore.resetSealedPassphrase() }
        coVerify(exactly = 0) { settingsRepository.setEncryptCache(any()) }
    }

    @Test
    fun `a nativeOpen failure while probing the encrypted open fails closed`() = runTest {
        every { settingsRepository.settings } returns flowOf(AppSettings(encryptCache = true, appLock = false))
        // Issue #359 gap 2 (SECONDARY): loadLibrary succeeds, but the REAL keyed open throws an
        // UnsatisfiedLinkError at SQLiteConnection.nativeOpen — the exact #359 signature. The provisioner
        // probes that open INSIDE the fail-closed handler, so it converts here rather than letting Room's
        // later deferred open (DatabaseModule) crash uncaught.
        every { DatabaseEncryption.probeKeyedOpen(any(), any()) } throws
            UnsatisfiedLinkError("SQLiteConnection.nativeOpen")

        val error = assertFailsWith<CacheEncryptionUnavailableException> { provisioner().prepareCache() }

        assertTrue(
            generateSequence(error.cause) { it.cause }.any { it is LinkageError },
            "the nativeOpen LinkageError must be preserved as the cause",
        )
        // The library loaded fine (loadLibrary was not the failure); it was the keyed nativeOpen probe.
        verify(exactly = 1) { DatabaseEncryption.ensureNativeLibraryLoaded() }
        verify(exactly = 1) { DatabaseEncryption.probeKeyedOpen(any(), PASSPHRASE) }
        // No fail-open side effects.
        verify(exactly = 0) { DatabaseFiles.clear(any()) }
        coVerify(exactly = 0) { keyStore.resetSealedPassphrase() }
    }

    @Test
    fun `a native-library load failure is not memoized and retries on the next open`() = runTest {
        every { settingsRepository.settings } returns flowOf(AppSettings(encryptCache = true, appLock = false))
        every { DatabaseEncryption.ensureNativeLibraryLoaded() } throws
            UnsatisfiedLinkError("dlopen failed: libsqlcipher.so is not 16 KB aligned")
        val provisioner = provisioner()

        assertFailsWith<CacheEncryptionUnavailableException> { provisioner.prepareCache() }
        assertFailsWith<CacheEncryptionUnavailableException> { provisioner.prepareCache() }

        // A failure is NOT memoized (unlike a success): each open re-runs the whole sequence, so the
        // migrator ran on BOTH attempts. That is what lets a later launch recover once the library loads.
        coVerify(exactly = 2) { accountDataMigrator.migrateIfNeeded() }
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
        // No auth seal exists (the passphrase was master-sealed), so nothing must be resealed.
        coVerify(exactly = 0) { keyStore.sealWithMaster() }
    }

    @Test
    fun `decrypt-on-disable releases a lingering auth seal by resealing under the master key`() = runTest {
        // Issue #479: after the decrypt-to-plaintext conversion the auth seal is an orphan — nothing
        // needs it to open the DB, but its presence keeps the app in the transitional window where
        // losing the auth-bound key forces a needless cache wipe. It must be resealed under the
        // master key AFTER the file conversion (the passphrase is still in hand on this path).
        every { settingsRepository.settings } returns flowOf(AppSettings(encryptCache = false, appLock = true))
        every { DatabaseEncryption.isEncrypted(any()) } returns true
        coEvery { keyStore.hasAuthSealedPassphrase() } returns true

        val mode = provisioner().prepareCache()

        assertEquals(CacheOpenMode.Plaintext, mode)
        coVerifyOrder {
            keyStore.resolvePassphrase(true)
            DatabaseEncryption.ensurePlaintext(any(), PASSPHRASE)
            keyStore.sealWithMaster()
        }
    }

    @Test
    fun `a failed reseal after decrypt-on-disable is non-fatal and still opens plaintext`() = runTest {
        // The reseal is best-effort: the cache is already plaintext, so a Keystore hiccup must not
        // fail the open (the lingering seal is handled defensively by the guard and the policy).
        every { settingsRepository.settings } returns flowOf(AppSettings(encryptCache = false, appLock = true))
        every { DatabaseEncryption.isEncrypted(any()) } returns true
        coEvery { keyStore.hasAuthSealedPassphrase() } returns true
        coEvery { keyStore.sealWithMaster() } throws IllegalStateException("keystore busy")

        val mode = provisioner().prepareCache()

        assertEquals(CacheOpenMode.Plaintext, mode)
        coVerify(exactly = 1) { keyStore.sealWithMaster() }
        // Fail soft, never destructive: the seal is left alone rather than reset/wiped.
        coVerify(exactly = 0) { keyStore.resetSealedPassphrase() }
        verify(exactly = 0) { DatabaseFiles.clear(any()) }
    }

    @Test
    fun `a plaintext cache with encryption off touches neither the passphrase nor a conversion`() = runTest {
        val mode = provisioner().prepareCache() // defaults: encryptCache = false, file not encrypted

        assertEquals(CacheOpenMode.Plaintext, mode)
        coVerify(exactly = 0) { keyStore.resolvePassphrase(any()) }
        verify(exactly = 0) { DatabaseEncryption.ensureEncrypted(any(), any()) }
        verify(exactly = 0) { DatabaseEncryption.ensurePlaintext(any(), any()) }
        // A plaintext cache opens with the framework helper, never SQLCipher, so it must not touch the
        // native library — the counterpart to the encrypted path's mandatory load above — nor probe a
        // keyed open (issue #359: the probe belongs to the encrypted branch only).
        verify(exactly = 0) { DatabaseEncryption.ensureNativeLibraryLoaded() }
        verify(exactly = 0) { DatabaseEncryption.probeKeyedOpen(any(), any()) }
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
