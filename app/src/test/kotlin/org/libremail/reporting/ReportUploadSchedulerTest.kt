// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.reporting

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import javax.inject.Provider
import kotlin.test.assertSame

/**
 * Enqueue is a thin wrapper over WorkManager, so these pin the behaviour it carries: the per-report
 * unique-work name and the REPLACE policy that lets a fresh Submit tap override a pending retry.
 * Injecting a `Provider<WorkManager>` (mirroring `SyncScheduler`) is what makes this JVM-testable — the
 * scheduler no longer reaches for the un-stubbable `WorkManager.getInstance` static, which MockK can't
 * stub on the abstract `WorkManager` (`AbstractMethodError`) that held this class off the unit suite
 * (issue #257).
 */
class ReportUploadSchedulerTest {

    private val workManager = mockk<WorkManager>(relaxed = true)
    private val scheduler = ReportUploadScheduler(Provider { workManager })

    @Test
    fun `enqueue replaces any pending upload for the same report id`() {
        scheduler.enqueue("rid")

        verify {
            workManager.enqueueUniqueWork(
                "libremail_report_upload_rid",
                ExistingWorkPolicy.REPLACE,
                any<OneTimeWorkRequest>(),
            )
        }
    }

    @Test
    fun `statusFlow observes the unique work for that report id`() {
        val flow = flowOf(emptyList<WorkInfo>())
        every { workManager.getWorkInfosForUniqueWorkFlow("libremail_report_upload_rid") } returns flow

        assertSame(flow, scheduler.statusFlow("rid"))
    }
}
