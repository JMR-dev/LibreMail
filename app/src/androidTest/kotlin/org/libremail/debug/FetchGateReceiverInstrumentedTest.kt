// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.debug

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import dagger.Lazy
import io.mockk.coEvery
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.data.security.EncryptedCacheGuard
import org.libremail.data.security.PassphraseSession
import org.libremail.data.settings.AppSettings
import org.libremail.data.settings.SettingsRepository
import org.libremail.data.sync.BackfillWorker
import org.libremail.data.sync.DebugFetchGate
import org.libremail.data.sync.FetchScope
import org.libremail.data.sync.MailBackfiller
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device proof of the debug-only fetch gate (issue #393): the adb-reachable [FetchGateReceiver]
 * updates [DebugFetchGate] and returns the resulting state as ordered-broadcast result data (exactly
 * what `adb shell am broadcast ... FETCH_GATE` prints back to the harness), and a gated proactive path
 * ([BackfillWorker]) genuinely defers while an un-gated path keeps running. The broadcast is sent
 * ordered — the same delivery mode `am broadcast` uses — so [BroadcastReceiver.getResultData] on the
 * final receiver reads back what the gate set, with no logcat race.
 *
 * The worker-deferral cases reuse `WorkerCacheLockDeferralInstrumentedTest`'s approach: build a
 * [BackfillWorker] with a real, never-unlocked-or-off [EncryptedCacheGuard] and a `Lazy` [MailBackfiller]
 * whose resolution is observable, so "the gate deferred before touching the DB" is proven by the `Lazy`
 * never being resolved.
 */
@RunWith(AndroidJUnit4::class)
class FetchGateReceiverInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // A fresh, real, never-unlocked session per test — so the real guard reports UNLOCKED only because
    // app-lock is off (see [unlockedGuard]), never because of leftover auth state.
    private val session = PassphraseSession()

    @Before
    @After
    fun resetGate() {
        DebugFetchGate.reset()
        unmockkAll()
    }

    @Test
    fun pauseUpdatesTheGateAndReturnsTheReadBack() {
        val data = sendGateBroadcast(FetchGateReceiver.ACTION_PAUSE, "backfill,prefetch")

        assertEquals("paused=[backfill,prefetch]", data)
        assertTrue(DebugFetchGate.isPaused(FetchScope.BACKFILL))
        assertTrue(DebugFetchGate.isPaused(FetchScope.PREFETCH))
    }

    @Test
    fun resumeAllClearsTheGateAndReturnsAnEmptyReadBack() {
        sendGateBroadcast(FetchGateReceiver.ACTION_PAUSE, "all")

        val data = sendGateBroadcast(FetchGateReceiver.ACTION_RESUME, "all")

        assertEquals("paused=[]", data)
        assertFalse(DebugFetchGate.isPaused(FetchScope.BACKFILL))
        assertFalse(DebugFetchGate.isPaused(FetchScope.PREFETCH))
    }

    @Test
    fun queryReadsBackTheStateWithoutMutatingIt() {
        sendGateBroadcast(FetchGateReceiver.ACTION_PAUSE, "backfill")

        val data = sendGateBroadcast(FetchGateReceiver.ACTION_QUERY, scope = null)

        assertEquals("paused=[backfill]", data)
        assertTrue(DebugFetchGate.isPaused(FetchScope.BACKFILL))
        assertFalse(DebugFetchGate.isPaused(FetchScope.PREFETCH))
    }

    @Test
    fun aBackfillPausedGateDefersTheBackfillWorkerWithoutResolvingTheBackfiller() = runBlocking<Unit> {
        sendGateBroadcast(FetchGateReceiver.ACTION_PAUSE, "backfill")
        val lazyBackfiller = mockk<Lazy<MailBackfiller>>()
        val worker = TestListenableWorkerBuilder<BackfillWorker>(context)
            .setWorkerFactory(backfillWorkerFactory(lazyBackfiller, unlockedGuard()))
            .build()

        val result = withTimeout(TIMEOUT_MS) { worker.doWork() }

        assertEquals(Result.retry(), result)
        // The gate deferred BEFORE any DB-backed work — the Lazy was never resolved.
        verify(exactly = 0) { lazyBackfiller.get() }
    }

    @Test
    fun aPrefetchOnlyPauseLeavesTheBackfillWorkerRunning() = runBlocking<Unit> {
        // The worker gate honours BACKFILL only; pausing PREFETCH must NOT defer history paging — the
        // on-device analogue of "on-demand open and header sync stay live while prefetch is paused".
        sendGateBroadcast(FetchGateReceiver.ACTION_PAUSE, "prefetch")
        val backfiller = mockk<MailBackfiller> { coEvery { runBackfill(any()) } returns false }
        val lazyBackfiller = mockk<Lazy<MailBackfiller>> { every { get() } returns backfiller }
        val worker = TestListenableWorkerBuilder<BackfillWorker>(context)
            .setWorkerFactory(backfillWorkerFactory(lazyBackfiller, unlockedGuard()))
            .build()

        val result = withTimeout(TIMEOUT_MS) { worker.doWork() }

        assertEquals(Result.success(), result)
        verify { lazyBackfiller.get() }
    }

    /**
     * Sends the [FetchGateReceiver.ACTION] broadcast to the receiver by explicit component (mirroring
     * `am broadcast -n`), ordered, and returns the result data the receiver set (the harness read-back).
     */
    private fun sendGateBroadcast(action: String, scope: String?): String {
        val latch = CountDownLatch(1)
        val readBack = arrayOfNulls<String>(1)
        val intent = Intent(FetchGateReceiver.ACTION).apply {
            component = ComponentName(context, FetchGateReceiver::class.java)
            putExtra(FetchGateReceiver.EXTRA_ACTION, action)
            if (scope != null) putExtra(FetchGateReceiver.EXTRA_SCOPE, scope)
        }
        context.sendOrderedBroadcast(
            intent,
            null,
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    readBack[0] = resultData
                    latch.countDown()
                }
            },
            null,
            0,
            null,
            null,
        )
        assertTrue("gate broadcast timed out", latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
        return requireNotNull(readBack[0]) { "receiver set no result data" }
    }

    /** A real [EncryptedCacheGuard] reporting UNLOCKED (app-lock off) — so only the gate can defer. */
    private fun unlockedGuard(): EncryptedCacheGuard {
        val settingsRepository = mockk<SettingsRepository>()
        every { settingsRepository.settings } returns flowOf(AppSettings(appLock = false, encryptCache = true))
        return EncryptedCacheGuard(settingsRepository, session)
    }

    private fun backfillWorkerFactory(lazyBackfiller: Lazy<MailBackfiller>, cacheGuard: EncryptedCacheGuard) =
        object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ) = BackfillWorker(appContext, workerParameters, lazyBackfiller, cacheGuard)
        }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
