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
import java.net.HttpURLConnection
import java.net.URL

/**
 * Posts a single user-submitted report to the ingest endpoint. It runs ONLY when the user tapped
 * Submit (enqueued by [ReportUploadScheduler]); nothing here runs automatically. The [endpoint] is
 * injected (see [DebugReportEndpoint]) from `BuildConfig.DEBUG_REPORT_ENDPOINT` — empty by default,
 * because the ingest server (issue #34) is separate infrastructure, so submissions no-op with a clear
 * failure until an endpoint is set.
 *
 * Before anything leaves the device the report is (1) run through [ReportAnonymizer] — a best-effort
 * final PII-redaction pass over the free-text/log surfaces — and (2) sealed by [ReportPayloadEncryptor]
 * to the maintainer's public key, so the wire payload is an opaque encrypted envelope, not the
 * plaintext JSON. The worker FAILS CLOSED (issue #34): if no encryption key is configured, or sealing
 * throws, it returns a failure and sends nothing rather than transmit an unencrypted report.
 */
@HiltWorker
class ReportUploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val store: ReportStore,
    private val anonymizer: ReportAnonymizer,
    private val encryptor: ReportPayloadEncryptor,
    @DebugReportEndpoint private val endpoint: String,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_REPORT_ID) ?: return Result.success()
        val report = store.find(id) ?: return Result.success() // discarded before the job ran
        val body = prepareBody(report) ?: return Result.failure()
        return withContext(Dispatchers.IO) {
            runCatching { post(endpoint, body) }.fold(
                onSuccess = { code -> onResponse(code, id) },
                onFailure = { e ->
                    AppLog.w(TAG, "Debug-report upload hit a network error; retrying if attempts remain", e)
                    retryOrFail()
                },
            )
        }
    }

    /**
     * Anonymizes then seals [report] for upload, or returns null (with a logged reason) to abort the
     * send. Null on: no endpoint configured, no encryption key configured, or a sealing failure — in
     * every case the worker fails closed rather than transmit anything unencrypted.
     */
    private fun prepareBody(report: DebugReport): String? {
        if (endpoint.isBlank()) {
            AppLog.w(TAG, "Report submit requested but no ingest endpoint is configured in this build; not sending")
            return null
        }
        if (!encryptor.isConfigured()) {
            AppLog.e(TAG, "Report submit aborted: no payload encryption key configured; refusing to send unencrypted")
            return null
        }
        val anonymized = anonymizer.anonymize(report)
        if (anonymizer.hasResidualPii(anonymized)) {
            AppLog.w(TAG, "A PII-shaped token survived anonymization; it was redacted best-effort before upload")
        }
        return runCatching { encryptor.encrypt(anonymized.toSubmissionPayload()) }
            .onSuccess { AppLog.i(TAG, "Sealed a debug report; uploading (attempt ${runAttemptCount + 1})") }
            .getOrElse { e ->
                AppLog.e(TAG, "Debug-report payload encryption failed; not sending", e)
                null
            }
    }

    private fun onResponse(code: Int, id: String): Result = when {
        code in SUCCESS_CODES -> {
            AppLog.i(TAG, "Debug report delivered (HTTP $code); dropping the local copy")
            store.delete(id) // delivered — drop the local copy
            Result.success()
        }
        code in SERVER_ERROR_CODES -> {
            AppLog.w(TAG, "Debug-report ingest returned HTTP $code; retrying if attempts remain")
            retryOrFail() // transient server-side failure
        }
        else -> {
            AppLog.w(TAG, "Debug-report ingest rejected the report (HTTP $code); giving up")
            Result.failure() // client error (4xx) — retrying won't help
        }
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
        private const val TAG = "ReportUploadWorker"
        private val SUCCESS_CODES = 200..299
        private val SERVER_ERROR_CODES = 500..599
        private const val TIMEOUT_MS = 15_000
        private const val MAX_ATTEMPTS = 5
    }
}
