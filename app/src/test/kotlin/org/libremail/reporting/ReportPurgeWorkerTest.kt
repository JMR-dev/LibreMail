// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.reporting

import androidx.work.ListenableWorker
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [ReportPurgeWorker] deletes locally-stored reports older than 30 days (issue #239). Pins the cutoff
 * it computes and that a failure retries rather than silently dropping the housekeeping.
 */
class ReportPurgeWorkerTest {

    private val store = mockk<ReportStore>()

    private fun worker() = ReportPurgeWorker(mockk(relaxed = true), mockk(relaxed = true), store)

    @Test
    fun `purges reports older than 30 days and succeeds`() = runTest {
        val cutoff = slot<Long>()
        every { store.purgeOlderThan(capture(cutoff)) } returns 3

        assertEquals(ListenableWorker.Result.success(), worker().doWork())

        val expected = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
        assertTrue(
            abs(cutoff.captured - expected) < TimeUnit.MINUTES.toMillis(1),
            "cutoff should be ~30 days before now",
        )
    }

    @Test
    fun `retries when the purge fails`() = runTest {
        every { store.purgeOlderThan(any()) } throws IllegalStateException("io error")

        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
    }
}
