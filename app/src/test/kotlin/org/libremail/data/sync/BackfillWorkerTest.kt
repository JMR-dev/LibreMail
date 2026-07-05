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

    private fun worker() = BackfillWorker(mockk(relaxed = true), mockk(relaxed = true), lazyBackfiller, cacheGuard)

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
    fun tearDown() = unmockkAll()

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
    fun `retries when backfilling throws`() = runTest {
        coEvery { cacheGuard.isCacheLocked() } returns false
        coEvery { backfiller.runBackfill(any()) } throws IllegalStateException("boom")

        assertEquals(Result.retry(), worker().doWork())
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

        val entry = logBuffer.snapshot().single()
        assertEquals('I', entry.level)
        assertEquals("backfill worker: success", entry.message)
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
}
