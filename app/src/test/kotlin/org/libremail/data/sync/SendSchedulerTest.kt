// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Test

/**
 * [SendScheduler] is a thin wrapper over WorkManager; this pins the behaviour that carries meaning —
 * the outbox drain is enqueued as a single unique job with REPLACE, so a newly-queued message (or a
 * manual retry) starts a fresh drain rather than waiting behind a pending backoff.
 */
class SendSchedulerTest {

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `sendNow enqueues a unique send-outbox job with REPLACE`() {
        mockkObject(WorkManager.Companion)
        val workManager = mockk<WorkManager>(relaxed = true)
        every { WorkManager.getInstance(any()) } returns workManager

        SendScheduler(mockk(relaxed = true)).sendNow()

        verify {
            workManager.enqueueUniqueWork(
                "libremail_send_outbox",
                ExistingWorkPolicy.REPLACE,
                any<OneTimeWorkRequest>(),
            )
        }
    }
}
