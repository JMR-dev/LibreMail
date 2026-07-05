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
 * [PruneWorker] must not touch the database while the encrypted cache is locked: opening it would park
 * this WorkManager thread on an unsatisfiable passphrase await (and wedge the shared serial executor).
 * It gates on [EncryptedCacheGuard] and only resolves the (`Lazy`) [MailPruner] once unlocked — the
 * invariant every pre-auth DB entry point shares with `SyncWorker`/`SendWorker`. Also covers issue
 * #329's AppLog breadcrumbs on the deferred/success/retry outcomes.
 */
class PruneWorkerTest {

    private val pruner = mockk<MailPruner>()
    private val lazyPruner = mockk<Lazy<MailPruner>> { every { get() } returns pruner }
    private val cacheGuard = mockk<EncryptedCacheGuard>()
    private val logBuffer = RingLogBuffer()

    private fun worker() = PruneWorker(mockk(relaxed = true), mockk(relaxed = true), lazyPruner, cacheGuard)

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
    fun `retries without resolving the pruner when the cache is locked`() = runTest {
        coEvery { cacheGuard.isCacheLocked() } returns true

        assertEquals(Result.retry(), worker().doWork())

        // The whole point of the guard: the DB-backed dependency is never even resolved while locked.
        verify(exactly = 0) { lazyPruner.get() }
        coVerify(exactly = 0) { pruner.prune(any()) }
    }

    @Test
    fun `prunes and succeeds when the cache is unlocked`() = runTest {
        coEvery { cacheGuard.isCacheLocked() } returns false
        coEvery { pruner.prune(any()) } returns 0

        assertEquals(Result.success(), worker().doWork())

        coVerify(exactly = 1) { pruner.prune(any()) }
    }

    @Test
    fun `retries when pruning throws`() = runTest {
        coEvery { cacheGuard.isCacheLocked() } returns false
        coEvery { pruner.prune(any()) } throws IllegalStateException("boom")

        assertEquals(Result.retry(), worker().doWork())
    }

    // --- issue #329: AppLog breadcrumbs ---------------------------------------------------------

    @Test
    fun `logs a deferred breadcrumb when the cache is locked, without touching the pruner`() = runTest {
        coEvery { cacheGuard.isCacheLocked() } returns true

        worker().doWork()

        val entry = logBuffer.snapshot().single()
        assertEquals('I', entry.level)
        assertEquals("prune deferred: cache locked", entry.message)
    }

    @Test
    fun `logs a success breadcrumb when pruning succeeds`() = runTest {
        coEvery { cacheGuard.isCacheLocked() } returns false
        coEvery { pruner.prune(any()) } returns 5

        worker().doWork()

        val entry = logBuffer.snapshot().single()
        assertEquals('I', entry.level)
        assertEquals("prune worker: success", entry.message)
    }

    @Test
    fun `logs a scrubbed retry breadcrumb when pruning throws`() = runTest {
        coEvery { cacheGuard.isCacheLocked() } returns false
        coEvery { pruner.prune(any()) } throws IllegalStateException("auth failed for a@example.org")

        worker().doWork()

        val entry = logBuffer.snapshot().single()
        assertEquals('W', entry.level)
        assertTrue(entry.message.startsWith("prune worker: retry"), entry.message)
        assertTrue(entry.message.contains("IllegalStateException"), entry.message)
        assertFalse(entry.message.contains("a@example.org"), entry.message)
    }
}
