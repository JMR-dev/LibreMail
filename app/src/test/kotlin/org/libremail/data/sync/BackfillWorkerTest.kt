// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import android.util.Log
import androidx.work.ListenableWorker.Result
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.data.local.CacheEncryptionUnavailableException
import org.libremail.data.security.EncryptedCacheGuard
import org.libremail.reporting.AppLog
import org.libremail.reporting.RingLogBuffer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [BackfillWorker] must defer while the encrypted cache is locked rather than park this WorkManager
 * thread opening the DB. It gates on [EncryptedCacheGuard], resolving the (`Lazy`) [MailBackfiller]
 * only once unlocked — the same pre-auth invariant `SyncWorker`/`SendWorker` enforce. Also covers issue
 * #329's AppLog breadcrumbs on the deferred/success/retry outcomes.
 */
class BackfillWorkerTest {

    private val backfiller = mockk<MailBackfiller>()
    private val lazyBackfiller = mockk<Lazy<MailBackfiller>> { every { get() } returns backfiller }
    private val cacheGuard = mockk<EncryptedCacheGuard>()
    private val logBuffer = RingLogBuffer()

    // A real pacer (#356) with a real, idle InteractiveImapGate: the worker's slice-chaining loop runs
    // through it, so these tests exercise the actual cooldown/cap wiring. Cooldowns are virtual under
    // runTest, so they cost the tests no wall-clock time.
    private val pacer = BackfillPacer(InteractiveImapGate())

    private fun worker() =
        BackfillWorker(mockk(relaxed = true), mockk(relaxed = true), lazyBackfiller, cacheGuard, pacer)

    @Before
    fun setUp() {
        // `android.util.Log` is a no-op stub under plain JVM unit tests, so it is statically mocked here,
        // mirroring org.libremail.reporting.AppLogTest — doWork() now breadcrumbs through AppLog.
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.d(any(), any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        AppLog.install(logBuffer)
    }

    @After
    fun tearDown() {
        unmockkAll()
        DebugFetchGate.reset() // the gate is a process-global object; don't leak a pause to other tests
    }

    @Test
    fun `retries without resolving the backfiller when the cache is locked`() = runTest {
        coEvery { cacheGuard.isCacheLocked() } returns true

        assertEquals(Result.retry(), worker().doWork())

        verify(exactly = 0) { lazyBackfiller.get() }
        coVerify(exactly = 0) { backfiller.runBackfill(any()) }
    }

    @Test
    fun `chains slices to completion and succeeds when the cache is unlocked`() = runTest {
        coEvery { cacheGuard.isCacheLocked() } returns false
        // true then false: one slice still has pages, the next reports done — the worker loops until false.
        coEvery { backfiller.runBackfill(any()) } returnsMany listOf(true, false)

        assertEquals(Result.success(), worker().doWork())

        coVerify(exactly = 2) { backfiller.runBackfill(any()) }
    }

    @Test
    fun `paces the run by capping its slices instead of paging flat-out forever (issue #356)`() = runTest {
        coEvery { cacheGuard.isCacheLocked() } returns false
        // A large mailbox reports moreWork forever; the pacer's per-run cap must stop the run anyway so it
        // can't monopolise the account for the whole session (the flat-out storm #356 fixes).
        coEvery { backfiller.runBackfill(any()) } returns true

        assertEquals(Result.success(), worker().doWork())

        val cap = BackfillPacer.MAX_SLICES_PER_RUN
        coVerify(exactly = cap) { backfiller.runBackfill(any()) }
        assertTrue(
            logBuffer.snapshot().any {
                it.message == "backfill run capped at $cap slice(s); deferring to periodic cadence"
            },
            "the capped run logs a PII-free breadcrumb",
        )
    }

    @Test
    fun `retries when backfilling throws`() = runTest {
        coEvery { cacheGuard.isCacheLocked() } returns false
        coEvery { backfiller.runBackfill(any()) } throws IllegalStateException("boom")

        assertEquals(Result.retry(), worker().doWork())
    }

    @Test
    fun `defers with a retry and a distinct breadcrumb when the encrypted cache is unavailable`() = runTest {
        // Issue #359: the DB open fails because SQLCipher's native library is unavailable. It lands in the
        // worker's runCatching (thrown inside runBackfill()), so it retries — but it must now log a DISTINCT
        // breadcrumb (not the generic retry, and not mistaken for a cancellation) and still never crash.
        coEvery { cacheGuard.isCacheLocked() } returns false
        coEvery { backfiller.runBackfill(any()) } throws
            CacheEncryptionUnavailableException(UnsatisfiedLinkError("SQLiteConnection.nativeOpen"))

        assertEquals(Result.retry(), worker().doWork())

        val entry = logBuffer.snapshot().single()
        assertEquals('W', entry.level)
        assertTrue(entry.message.startsWith("backfill deferred: encrypted cache unavailable"), entry.message)
    }

    // --- issue #329: AppLog breadcrumbs ---------------------------------------------------------

    @Test
    fun `logs a deferred breadcrumb when the cache is locked, without touching the backfiller`() = runTest {
        coEvery { cacheGuard.isCacheLocked() } returns true

        worker().doWork()

        val entry = logBuffer.snapshot().single()
        assertEquals('I', entry.level)
        assertEquals("backfill deferred: cache locked", entry.message)
    }

    @Test
    fun `logs a success breadcrumb once every chained slice completes`() = runTest {
        coEvery { cacheGuard.isCacheLocked() } returns false
        coEvery { backfiller.runBackfill(any()) } returnsMany listOf(true, false)

        worker().doWork()

        // The pacer logs one inter-slice cooldown breadcrumb between the two slices (#356), so the run's
        // final line — not the only line — is the success breadcrumb.
        val entry = logBuffer.snapshot().single { it.message == "backfill worker: success" }
        assertEquals('I', entry.level)
    }

    @Test
    fun `logs a scrubbed retry breadcrumb when backfilling throws`() = runTest {
        coEvery { cacheGuard.isCacheLocked() } returns false
        coEvery { backfiller.runBackfill(any()) } throws
            IllegalStateException("auth failed for a@example.org")

        worker().doWork()

        val entry = logBuffer.snapshot().single()
        assertEquals('W', entry.level)
        assertTrue(entry.message.startsWith("backfill worker: retry"), entry.message)
        assertTrue(entry.message.contains("IllegalStateException"), entry.message)
        assertFalse(entry.message.contains("a@example.org"), entry.message)
    }

    // --- issue #393: debug-only fetch gate ------------------------------------------------------

    @Test
    fun `defers without resolving the backfiller when the fetch gate pauses backfill`() = runTest {
        // Cache unlocked, so the ONLY reason to defer is the gate. (BuildConfig.DEBUG is true under
        // testDebugUnitTest, so the gate branch is live.)
        coEvery { cacheGuard.isCacheLocked() } returns false
        DebugFetchGate.pause(setOf(FetchScope.BACKFILL))

        assertEquals(Result.retry(), worker().doWork())

        // Same invariant as the cache-lock deferral: never resolve the DB-backed collaborator.
        verify(exactly = 0) { lazyBackfiller.get() }
        coVerify(exactly = 0) { backfiller.runBackfill(any()) }
    }

    @Test
    fun `pausing only prefetch does NOT defer the backfill worker`() = runTest {
        // The worker gate honours BACKFILL only — a PREFETCH pause must leave history paging running.
        coEvery { cacheGuard.isCacheLocked() } returns false
        coEvery { backfiller.runBackfill(any()) } returns false
        DebugFetchGate.pause(setOf(FetchScope.PREFETCH))

        assertEquals(Result.success(), worker().doWork())

        coVerify(exactly = 1) { backfiller.runBackfill(any()) }
    }

    @Test
    fun `logs a deferred breadcrumb when the fetch gate pauses backfill`() = runTest {
        coEvery { cacheGuard.isCacheLocked() } returns false
        DebugFetchGate.pause(setOf(FetchScope.BACKFILL))

        worker().doWork()

        val entry = logBuffer.snapshot().single()
        assertEquals('I', entry.level)
        assertEquals("backfill deferred: fetch-gate paused", entry.message)
    }
}
