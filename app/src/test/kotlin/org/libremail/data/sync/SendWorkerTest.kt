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
import org.libremail.data.attachment.AttachmentUriGrants
import org.libremail.data.local.dao.AccountDao
import org.libremail.data.local.dao.OutboxDao
import org.libremail.data.security.EncryptedCacheGuard
import org.libremail.mail.GraphSender
import org.libremail.mail.SmtpSender
import kotlin.test.assertEquals

/**
 * [SendWorker] already gates on [EncryptedCacheGuard]; this locks that invariant in — while the cache
 * is locked it must defer without resolving any of its (`Lazy`) DB-backed dependencies (resolving any
 * of them opens the Room DB, which blocks on the passphrase await).
 */
class SendWorkerTest {

    private val outboxDao = mockk<OutboxDao>()
    private val accountDao = mockk<AccountDao>()
    private val connectionFactory = mockk<MailConnectionFactory>()
    private val attachmentUriGrants = mockk<AttachmentUriGrants>()
    private val lazyOutbox = mockk<Lazy<OutboxDao>> { every { get() } returns outboxDao }
    private val lazyAccount = mockk<Lazy<AccountDao>> { every { get() } returns accountDao }
    private val lazyConnection = mockk<Lazy<MailConnectionFactory>> { every { get() } returns connectionFactory }
    private val lazyGrants = mockk<Lazy<AttachmentUriGrants>> { every { get() } returns attachmentUriGrants }
    private val cacheGuard = mockk<EncryptedCacheGuard>()

    private fun worker() = SendWorker(
        mockk(relaxed = true),
        mockk(relaxed = true),
        lazyOutbox,
        lazyAccount,
        mockk<SmtpSender>(),
        mockk<GraphSender>(),
        lazyConnection,
        cacheGuard,
        lazyGrants,
    )

    @Test
    fun `retries without resolving any DB dependency when the cache is locked`() = runTest {
        coEvery { cacheGuard.isCacheLocked() } returns true

        assertEquals(Result.retry(), worker().doWork())

        verify(exactly = 0) { lazyOutbox.get() }
        verify(exactly = 0) { lazyAccount.get() }
        verify(exactly = 0) { lazyConnection.get() }
        verify(exactly = 0) { lazyGrants.get() }
    }

    @Test
    fun `drains the outbox when the cache is unlocked`() = runTest {
        coEvery { cacheGuard.isCacheLocked() } returns false
        coEvery { outboxDao.getAll() } returns emptyList()

        assertEquals(Result.success(), worker().doWork())

        coVerify(exactly = 1) { outboxDao.getAll() }
    }
}
