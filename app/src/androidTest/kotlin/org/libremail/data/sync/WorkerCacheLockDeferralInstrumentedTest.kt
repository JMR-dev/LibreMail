// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import dagger.Lazy
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.data.security.EncryptedCacheGuard
import org.libremail.data.security.PassphraseSession
import org.libremail.data.settings.AppSettings
import org.libremail.data.settings.SettingsRepository

/**
 * On-device proof that [PruneWorker] and [BackfillWorker] defer (`Result.retry()`) while the encrypted
 * cache is locked, driving them with the REAL [EncryptedCacheGuard] rather than the mocked guard the JVM
 * `PruneWorkerTest`/`BackfillWorkerTest` use (issue #225). [EncryptedCacheGuard] depends only on
 * [SettingsRepository] and [PassphraseSession] — never the Keystore or `BiometricPrompt` — so the locked
 * state is reproduced here with no device auth: a fixed [SettingsRepository] (mocked the same way
 * `DatabaseProvisionerInstrumentedTest` fakes its security/settings collaborators) plus a real,
 * never-unlocked [PassphraseSession].
 *
 * Caveat (see issue #226): the true "WorkManager cold-starts the process with no UI" can't be reproduced
 * in-process. The locked-[PassphraseSession] seam is the faithful stand-in; the auth-bound Keystore path
 * stays device-only (see `DatabaseKeyCipher`).
 *
 * "No `libremail.db` connection is opened" is proven by construction rather than by inspecting the
 * on-disk file: [PruneWorker]/[BackfillWorker] can only reach the database through the `Lazy`
 * [MailPruner]/[MailBackfiller] passed into their constructor (mirroring `DatabaseModule`'s real Hilt
 * wiring), and both gate on [EncryptedCacheGuard.isCacheLocked] BEFORE ever resolving that `Lazy`.
 * Asserting the `Lazy` is never resolved is therefore a direct, deterministic proof that no DAO method —
 * and so no Room/SQLCipher open — ran, without coupling the test to whatever else the shared
 * instrumentation process (or the real `libremail.db` file) happens to be doing.
 */
@RunWith(AndroidJUnit4::class)
class WorkerCacheLockDeferralInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // A fresh, real instance per test (JUnit4 builds a new test-class instance per method) — never
    // unlocked here, so isUnlocked() stays false exactly like a cold process start before the user has
    // authenticated.
    private val session = PassphraseSession()

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun pruneWorkerDefersWithoutResolvingThePrunerWhileTheRealGuardReportsLocked() = runBlocking<Unit> {
        val cacheGuard = guardFor(appLock = true, encryptCache = true)
        assertTrue("precondition: the real guard must report locked", cacheGuard.isCacheLocked())

        val lazyPruner = mockk<Lazy<MailPruner>>()
        val worker = TestListenableWorkerBuilder<PruneWorker>(context)
            .setWorkerFactory(pruneWorkerFactory(lazyPruner, cacheGuard))
            .build()

        val result = withTimeout(TIMEOUT_MS) { worker.doWork() }

        assertEquals(Result.retry(), result)
        // The invariant under test: a locked cache must never even resolve the DB-backed collaborator.
        verify(exactly = 0) { lazyPruner.get() }
    }

    @Test
    fun backfillWorkerDefersWithoutResolvingTheBackfillerWhileTheRealGuardReportsLocked() = runBlocking<Unit> {
        val cacheGuard = guardFor(appLock = true, encryptCache = true)
        assertTrue("precondition: the real guard must report locked", cacheGuard.isCacheLocked())

        val lazyBackfiller = mockk<Lazy<MailBackfiller>>()
        val worker = TestListenableWorkerBuilder<BackfillWorker>(context)
            .setWorkerFactory(backfillWorkerFactory(lazyBackfiller, cacheGuard))
            .build()

        val result = withTimeout(TIMEOUT_MS) { worker.doWork() }

        assertEquals(Result.retry(), result)
        verify(exactly = 0) { lazyBackfiller.get() }
    }

    /**
     * Contrast case so the two tests above can't be vacuously true: the same real guard, driven by the
     * same collaborator types, reports UNLOCKED once app-lock is off. [EncryptedCacheGuard] otherwise has
     * no test of its own anywhere in the suite (only callers mocking the whole guard), so this is also
     * this class's only direct coverage of its boolean logic.
     */
    @Test
    fun theRealGuardReportsUnlockedWhenAppLockIsOff() = runBlocking<Unit> {
        val cacheGuard = guardFor(appLock = false, encryptCache = true)

        assertFalse(cacheGuard.isCacheLocked())
    }

    /** Second branch of the same contrast: an authenticated session unlocks the cache even with app-lock on. */
    @Test
    fun theRealGuardReportsUnlockedOnceTheSessionIsUnlocked() = runBlocking<Unit> {
        val cacheGuard = guardFor(appLock = true, encryptCache = true)
        assertTrue("precondition: locked before authentication", cacheGuard.isCacheLocked())

        session.unlock(FAKE_PASSPHRASE)

        assertFalse(cacheGuard.isCacheLocked())
    }

    /** A real [EncryptedCacheGuard] over a fixed (mocked) [SettingsRepository] and the real [session]. */
    private fun guardFor(appLock: Boolean, encryptCache: Boolean): EncryptedCacheGuard {
        val settingsRepository = mockk<SettingsRepository>()
        val settings = AppSettings(appLock = appLock, encryptCache = encryptCache)
        every { settingsRepository.settings } returns flowOf(settings)
        return EncryptedCacheGuard(settingsRepository, session)
    }

    private fun pruneWorkerFactory(lazyPruner: Lazy<MailPruner>, cacheGuard: EncryptedCacheGuard) =
        object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ) = PruneWorker(appContext, workerParameters, lazyPruner, cacheGuard)
        }

    private fun backfillWorkerFactory(lazyBackfiller: Lazy<MailBackfiller>, cacheGuard: EncryptedCacheGuard) =
        object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ) = BackfillWorker(
                appContext,
                workerParameters,
                lazyBackfiller,
                cacheGuard,
                // A real pacer (#356); never reached here — the run defers on the locked cache first.
                BackfillPacer(InteractiveImapGate()),
            )
        }

    private companion object {
        // Generous bound: a locked run must fail fast (no passphrase await), so this is only ever
        // approached by a real regression — a passing run returns almost immediately.
        const val TIMEOUT_MS = 5_000L

        // 64 hex chars, matching DatabaseKeyStore's passphrase format. Never used to open a real database.
        const val FAKE_PASSPHRASE = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
