// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import javax.inject.Provider

/**
 * The enqueue methods are thin wrappers over WorkManager, so these tests pin the one thing that carries
 * behaviour: the existing-work policy each unique job is enqueued with.
 */
class SyncSchedulerTest {

    private val workManager = mockk<WorkManager>(relaxed = true)
    private val scheduler = SyncScheduler(Provider { workManager })

    // Issue #96: periodic jobs must re-enqueue with UPDATE (not KEEP). At every app start KEEP would pin
    // an already-installed device to the interval/constraints of whichever version first scheduled the
    // job, so cadence/constraint tuning never reached upgraders; UPDATE re-applies the current spec.

    @Test
    fun `periodic sync is enqueued with UPDATE so upgrades re-apply its schedule`() {
        scheduler.schedulePeriodicSync()

        verify {
            workManager.enqueueUniquePeriodicWork(
                "libremail_periodic_sync",
                ExistingPeriodicWorkPolicy.UPDATE,
                any<PeriodicWorkRequest>(),
            )
        }
    }

    @Test
    fun `periodic backfill is enqueued with UPDATE so upgrades re-apply its schedule`() {
        scheduler.schedulePeriodicBackfill()

        verify {
            workManager.enqueueUniquePeriodicWork(
                "libremail_periodic_backfill",
                ExistingPeriodicWorkPolicy.UPDATE,
                any<PeriodicWorkRequest>(),
            )
        }
    }

    @Test
    fun `periodic prune is enqueued with UPDATE so upgrades re-apply its schedule`() {
        scheduler.schedulePeriodicPrune()

        verify {
            workManager.enqueueUniquePeriodicWork(
                "libremail_periodic_prune",
                ExistingPeriodicWorkPolicy.UPDATE,
                any<PeriodicWorkRequest>(),
            )
        }
    }

    // The one-shot kicks are a separate concern from #96 and keep their existing policies: syncNow and
    // pruneNow REPLACE for a clean immediate attempt; backfillNow KEEPs an in-flight all-account run.

    @Test
    fun `syncNow replaces any pending one-shot sync`() {
        scheduler.syncNow()

        verify {
            workManager.enqueueUniqueWork(
                "libremail_oneshot_sync",
                ExistingWorkPolicy.REPLACE,
                any<OneTimeWorkRequest>(),
            )
        }
    }

    @Test
    fun `backfillNow keeps an already-running backfill`() {
        scheduler.backfillNow()

        verify {
            workManager.enqueueUniqueWork(
                "libremail_oneshot_backfill",
                ExistingWorkPolicy.KEEP,
                any<OneTimeWorkRequest>(),
            )
        }
    }

    @Test
    fun `pruneNow replaces any pending one-shot prune`() {
        scheduler.pruneNow()

        verify {
            workManager.enqueueUniqueWork(
                "libremail_oneshot_prune",
                ExistingWorkPolicy.REPLACE,
                any<OneTimeWorkRequest>(),
            )
        }
    }
}
