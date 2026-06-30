// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val mailSyncer: MailSyncer,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result =
        mailSyncer.syncAll().fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() },
        )
}
