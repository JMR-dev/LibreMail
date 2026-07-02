// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** Schedules background mail sync, full-history backfill, and retention pruning via WorkManager. */
@Singleton
class SyncScheduler @Inject constructor(
    // A Provider (not the WorkManager itself) so WorkManager.getInstance() is resolved lazily at
    // schedule time — never during Hilt's Application field injection, which can run before the
    // HiltWorkerFactory that on-demand WorkManager initialization needs is set.
    private val workManagerProvider: Provider<WorkManager>,
) {
    private val workManager get() = workManagerProvider.get()

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    // Backfill is bulk, non-urgent work: require a network AND a healthy battery so it never competes
    // with foreground use or drains the device while paging a large mailbox.
    private val backfillConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    // Pruning is purely local (no server calls), so it needs no network — only a healthy battery.
    private val pruneConstraint = Constraints.Builder()
        .setRequiresBatteryNotLow(true)
        .build()

    /** Periodic background sync (WorkManager's 15-minute floor). */
    fun schedulePeriodicSync() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraint)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK, PERIODIC_POLICY, request)
    }

    /** One-shot sync, e.g. right after an account is added. */
    fun syncNow() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(networkConstraint)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        workManager.enqueueUniqueWork(ONESHOT_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * Periodic full-history backfill (issue #12). Each run pages a bounded slice and persists its
     * boundary, so history fills in over successive runs.
     */
    fun schedulePeriodicBackfill() {
        val request = PeriodicWorkRequestBuilder<BackfillWorker>(30, TimeUnit.MINUTES)
            .setConstraints(backfillConstraint)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_BACKFILL, PERIODIC_POLICY, request)
    }

    /** Kicks an immediate backfill slice (e.g. just after an account is added) without waiting for the cadence. */
    fun backfillNow() {
        val request = OneTimeWorkRequestBuilder<BackfillWorker>()
            .setConstraints(backfillConstraint)
            .build()
        // KEEP: if a backfill is already running/enqueued it already covers every account, so don't
        // restart it; once that one finishes a later kick will start a fresh slice.
        workManager.enqueueUniqueWork(ONESHOT_BACKFILL, ExistingWorkPolicy.KEEP, request)
    }

    /** Periodic retention pruning (issue #13); also enforces age limits as messages get older over time. */
    fun schedulePeriodicPrune() {
        val request = PeriodicWorkRequestBuilder<PruneWorker>(12, TimeUnit.HOURS)
            .setConstraints(pruneConstraint)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_PRUNE, PERIODIC_POLICY, request)
    }

    /** Runs pruning promptly, e.g. right after the user tightens a retention limit. */
    fun pruneNow() {
        val request = OneTimeWorkRequestBuilder<PruneWorker>().build()
        workManager.enqueueUniqueWork(ONESHOT_PRUNE, ExistingWorkPolicy.REPLACE, request)
    }

    private companion object {
        const val PERIODIC_WORK = "libremail_periodic_sync"
        const val ONESHOT_WORK = "libremail_oneshot_sync"
        const val PERIODIC_BACKFILL = "libremail_periodic_backfill"
        const val ONESHOT_BACKFILL = "libremail_oneshot_backfill"
        const val PERIODIC_PRUNE = "libremail_periodic_prune"
        const val ONESHOT_PRUNE = "libremail_oneshot_prune"

        // UPDATE, not KEEP (issue #96). These periodic jobs are re-enqueued at every app start, so KEEP
        // pinned an already-installed device to the interval/constraints from the version that first
        // scheduled it — later tuning never reached upgraders. UPDATE re-applies the current spec while
        // preserving the running period's progress: an unchanged spec is effectively a no-op, so this
        // does NOT reset the schedule on launch the way REPLACE (cancel + re-enqueue) would. UPDATE is
        // available since WorkManager 2.8.
        val PERIODIC_POLICY = ExistingPeriodicWorkPolicy.UPDATE
    }
}
