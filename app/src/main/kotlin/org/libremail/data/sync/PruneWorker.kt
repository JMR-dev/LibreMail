// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Enforces device-only retention (issue #13) by running [MailPruner]. Purely local — it never
 * contacts the server — so it needs no network constraint. Retries with backoff on failure.
 */
@HiltWorker
class PruneWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val pruner: MailPruner,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = runCatching { pruner.prune() }.fold(
        onSuccess = { Result.success() },
        onFailure = { Result.retry() },
    )
}
