// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Schedules the outbox send worker via WorkManager. */
@Singleton
class SendScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Drain the outbox as soon as the network is available, retrying with backoff on failure. */
    fun sendNow() {
        val request = OneTimeWorkRequestBuilder<SendWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // APPEND_OR_REPLACE: ensure newly-queued mail gets a run without cancelling an in-flight send.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(SEND_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private companion object {
        const val SEND_WORK = "libremail_send_outbox"
    }
}
