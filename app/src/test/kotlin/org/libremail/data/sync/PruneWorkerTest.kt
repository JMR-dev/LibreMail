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
 * [PruneWorker] must not touch the database while the encrypted cache is locked: opening it would park
 * this WorkManager thread on an unsatisfiable passphrase await (and wedge the shared serial executor).
 * It gates on [EncryptedCacheGuard] and only resolves the (`Lazy`) [MailPruner] once unlocked — the
 * invariant every pre-auth DB entry point shares with `SyncWorker`/`SendWorker`.
 */
class PruneWorkerTest {

    private val pruner = mockk<MailPruner>()
    private val lazyPruner = mockk<Lazy<MailPruner>> { every { get() } returns pruner }
    private val cacheGuard = mockk<EncryptedCacheGuard>()

    private fun worker() = PruneWorker(mockk(relaxed = true), mockk(relaxed = true), lazyPruner, cacheGuard)

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
}
