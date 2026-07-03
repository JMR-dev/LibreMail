// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import androidx.work.ListenableWorker
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
 * [SyncWorker] already gates on [EncryptedCacheGuard]; this locks that invariant in as regression
 * cover for the class of bug fixed in PruneWorker/BackfillWorker — while the cache is locked it must
 * defer without resolving the (`Lazy`) [MailSyncer] (whose construction opens the Room DB).
 */
class SyncWorkerTest {

    private val mailSyncer = mockk<MailSyncer>()
    private val lazySyncer = mockk<Lazy<MailSyncer>> { every { get() } returns mailSyncer }
    private val cacheGuard = mockk<EncryptedCacheGuard>()

    private fun worker() = SyncWorker(mockk(relaxed = true), mockk(relaxed = true), lazySyncer, cacheGuard)

    @Test
    fun `retries without resolving the syncer when the cache is locked`() = runTest {
        coEvery { cacheGuard.isCacheLocked() } returns true

        assertEquals(ListenableWorker.Result.retry(), worker().doWork())

        verify(exactly = 0) { lazySyncer.get() }
        coVerify(exactly = 0) { mailSyncer.syncAll() }
    }

    @Test
    fun `syncs and succeeds when the cache is unlocked`() = runTest {
        coEvery { cacheGuard.isCacheLocked() } returns false
        coEvery { mailSyncer.syncAll() } returns Result.success(0)

        assertEquals(ListenableWorker.Result.success(), worker().doWork())

        coVerify(exactly = 1) { mailSyncer.syncAll() }
    }

    @Test
    fun `retries when syncing fails`() = runTest {
        coEvery { cacheGuard.isCacheLocked() } returns false
        coEvery { mailSyncer.syncAll() } returns Result.failure(IllegalStateException("boom"))

        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
    }
}
