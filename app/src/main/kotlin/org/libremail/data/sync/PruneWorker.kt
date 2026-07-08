// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.libremail.data.local.isCacheEncryptionUnavailable
import org.libremail.data.security.EncryptedCacheGuard
import org.libremail.reporting.AppLog

/**
 * Enforces device-only retention (issue #13) by running [MailPruner]. Purely local — it never
 * contacts the server — so it needs no network constraint. Retries with backoff on failure.
 */
@HiltWorker
class PruneWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    // Lazy: resolving MailPruner builds the Room DB graph, whose first query blocks while the encrypted
    // cache is locked. Resolve it only after the cache-lock check passes, so a locked run fails fast
    // instead of parking this thread on an unsatisfiable passphrase await (mirrors SyncWorker/SendWorker).
    private val pruner: Lazy<MailPruner>,
    private val cacheGuard: EncryptedCacheGuard,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        // Can't open the encrypted DB without the user present — retry later rather than parking a
        // WorkManager thread (which also wedges the shared serial executor) on an unsatisfiable await.
        if (cacheGuard.isCacheLocked()) {
            AppLog.i(TAG, "prune deferred: cache locked")
            return Result.retry()
        }
        return runCatching { pruner.get().prune() }.fold(
            onSuccess = {
                AppLog.i(TAG, "prune worker: success")
                Result.success()
            },
            onFailure = { error ->
                // A DB open that fails because SQLCipher's native library is unavailable (issue #359) lands
                // here (it is thrown inside the runCatching above); log it distinctly but still defer softly
                // — a later launch may load the library and recover — instead of a generic retry.
                if (error.isCacheEncryptionUnavailable()) {
                    AppLog.w(TAG, "prune deferred: encrypted cache unavailable (SQLCipher native library)", error)
                } else {
                    AppLog.w(TAG, "prune worker: retry", error)
                }
                Result.retry()
            },
        )
    }

    private companion object {
        const val TAG = "PruneWorker"
    }
}
