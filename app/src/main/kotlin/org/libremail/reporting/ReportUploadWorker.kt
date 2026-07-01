// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.reporting

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.libremail.BuildConfig
import java.net.HttpURLConnection
import java.net.URL

/**
 * Posts a single user-submitted report to the ingest endpoint. It runs ONLY when the user tapped
 * Submit (enqueued by [ReportUploadScheduler]); nothing here runs automatically. The endpoint is
 * [BuildConfig.DEBUG_REPORT_ENDPOINT] — empty by default, because the ingest server (issue #34) is
 * out of scope for this repo, so submissions no-op with a clear failure until an endpoint is set.
 */
@HiltWorker
class ReportUploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val store: ReportStore,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_REPORT_ID) ?: return Result.success()
        val report = store.find(id) ?: return Result.success() // discarded before the job ran
        val endpoint = BuildConfig.DEBUG_REPORT_ENDPOINT
        if (endpoint.isBlank()) return Result.failure() // no ingest server configured in this build
        return withContext(Dispatchers.IO) {
            runCatching { post(endpoint, report.toSubmissionPayload()) }.fold(
                onSuccess = { code -> onResponse(code, id) },
                onFailure = { retryOrFail() }, // network error — retry with backoff
            )
        }
    }

    private fun onResponse(code: Int, id: String): Result = when {
        code in SUCCESS_CODES -> {
            store.delete(id) // delivered — drop the local copy
            Result.success()
        }
        code in SERVER_ERROR_CODES -> retryOrFail() // transient server-side failure
        else -> Result.failure() // client error (4xx) — retrying won't help
    }

    private fun retryOrFail(): Result = if (runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()

    private fun post(endpoint: String, body: String): Int {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val KEY_REPORT_ID = "report_id"
        private val SUCCESS_CODES = 200..299
        private val SERVER_ERROR_CODES = 500..599
        private const val TIMEOUT_MS = 15_000
        private const val MAX_ATTEMPTS = 5
    }
}
