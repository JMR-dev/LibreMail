// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.libremail.data.security.EncryptedCacheGuard
import org.libremail.reporting.AppLog

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    // Lazy: constructing MailSyncer builds the Room DB, which blocks while the encrypted cache is
    // locked. Resolve it only after confirming the cache is unlocked, so a locked run fails fast.
    private val mailSyncer: Lazy<MailSyncer>,
    private val cacheGuard: EncryptedCacheGuard,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        // Can't open the encrypted DB without the user present — retry later rather than parking a
        // WorkManager thread (which also wedges the shared serial executor) on an unsatisfiable await.
        if (cacheGuard.isCacheLocked()) {
            AppLog.i(TAG, "sync deferred: cache locked")
            return Result.retry()
        }
        // syncAll()'s first DB access (and thus Room's deferred cache open) can throw if SQLCipher's
        // native library is unavailable on this device (issue #359) — that open fires OUTSIDE syncAll's own
        // runCatching, so without this guard it would escape doWork. Treat it as a soft retry, not a crash.
        return retryIfEncryptedCacheUnavailable(TAG) {
            mailSyncer.get().syncAll().fold(
                onSuccess = {
                    AppLog.i(TAG, "sync worker: success")
                    Result.success()
                },
                onFailure = { error ->
                    AppLog.w(TAG, "sync worker: retry", error)
                    Result.retry()
                },
            )
        }
    }

    private companion object {
        const val TAG = "SyncWorker"
    }
}
