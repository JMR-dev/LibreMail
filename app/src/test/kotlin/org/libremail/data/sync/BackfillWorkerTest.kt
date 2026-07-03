// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import androidx.work.ListenableWorker.Result
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.libremail.data.security.EncryptedCacheGuard
import kotlin.test.assertEquals

/**
 * [BackfillWorker] must defer while the encrypted cache is locked rather than park this WorkManager
 * thread opening the DB. It gates on [EncryptedCacheGuard], resolving the (`Lazy`) [MailBackfiller]
 * only once unlocked — the same pre-auth invariant `SyncWorker`/`SendWorker` enforce.
 */
class BackfillWorkerTest {

    private val backfiller = mockk<MailBackfiller>()
    private val lazyBackfiller = mockk<Lazy<MailBackfiller>> { every { get() } returns backfiller }
    private val cacheGuard = mockk<EncryptedCacheGuard>()

    private fun worker() = BackfillWorker(mockk(relaxed = true), mockk(relaxed = true), lazyBackfiller, cacheGuard)

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
}
