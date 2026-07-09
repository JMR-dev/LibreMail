// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail.graph

import org.json.JSONObject
import org.libremail.reporting.AppLog
import org.libremail.reporting.accountLogRef
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Uploads large content to Microsoft Graph in chunks via an upload session (issue #364). Graph caps a
 * one-shot (inline / single-request) upload at ~4 MB; anything larger must go through
 * `createUploadSession` and a series of ranged `PUT`s, which also keeps each request well inside the
 * 150 MB-per-5-minutes bandwidth window instead of one giant spike.
 *
 * Each `PUT` (and the session-create `POST`) runs through [GraphThrottle], so chunked uploads obey the
 * same concurrency cap and honor `Retry-After` between chunks — a throttle mid-upload pauses and
 * resumes rather than failing the whole transfer. Chunk size is a multiple of [CHUNK_MULTIPLE_BYTES]
 * (320 KiB), as Graph requires for every non-final chunk. Logging is PII-free.
 */
@Singleton
class GraphUploadSession @Inject constructor(private val throttle: GraphThrottle) {

    /** True when [sizeBytes] exceeds Graph's one-shot ceiling and must use a chunked upload session. */
    fun requiresUploadSession(sizeBytes: Long): Boolean = sizeBytes > LARGE_ATTACHMENT_THRESHOLD_BYTES

    /**
     * Creates an upload session at [createSessionUrl] (with [createSessionBody], e.g. the
     * `{"AttachmentItem": …}` descriptor) and uploads [content] to the returned `uploadUrl` in
     * [chunkSize]-byte ranges. Returns the final chunk's [GraphResponse] (Graph answers the last chunk
     * 200/201 and intermediate chunks 202). A non-2xx session-create, or a non-2xx chunk, stops the
     * upload and returns that response so the caller can react.
     *
     * Requires non-empty [content] and a [chunkSize] that is a positive multiple of
     * [CHUNK_MULTIPLE_BYTES] (both enforced by `require`), and a session response carrying an `uploadUrl`.
     */
    suspend fun upload(
        accountId: String,
        client: GraphHttpClient,
        createSessionUrl: String,
        createSessionBody: JSONObject,
        content: ByteArray,
        chunkSize: Int = DEFAULT_CHUNK_SIZE_BYTES,
    ): GraphResponse {
        require(content.isNotEmpty()) { "cannot upload empty content" }
        require(chunkSize > 0 && chunkSize % CHUNK_MULTIPLE_BYTES == 0) {
            "chunk size must be a positive multiple of $CHUNK_MULTIPLE_BYTES bytes"
        }
        val create = throttle.execute(
            accountId,
            client,
            GraphRequest(
                method = "POST",
                url = createSessionUrl,
                headers = mapOf(HEADER_CONTENT_TYPE to CONTENT_TYPE_JSON),
                body = createSessionBody.toString().toByteArray(Charsets.UTF_8),
            ),
        )
        if (!isHttpSuccess(create.status)) {
            AppLog.w(TAG, "graph upload session create failed ${accountLogRef(accountId)} status=${create.status}")
            return create
        }
        val uploadUrl = runCatching { JSONObject(create.body).optString("uploadUrl") }.getOrNull()
        require(!uploadUrl.isNullOrBlank()) { "upload session response carried no uploadUrl" }

        val total = content.size
        var offset = 0
        var chunks = 0
        var last = create
        while (offset < total) {
            val end = minOf(offset + chunkSize, total)
            last = throttle.execute(
                accountId,
                client,
                GraphRequest(
                    method = "PUT",
                    url = uploadUrl,
                    headers = mapOf(HEADER_CONTENT_RANGE to "bytes $offset-${end - 1}/$total"),
                    body = content.copyOfRange(offset, end),
                ),
            )
            chunks++
            if (!isHttpSuccess(last.status)) {
                AppLog.w(TAG, "graph upload chunk failed ${accountLogRef(accountId)} status=${last.status} at=$chunks")
                return last
            }
            offset = end
        }
        AppLog.i(TAG, "graph upload ok ${accountLogRef(accountId)} bytes=$total chunks=$chunks")
        return last
    }

    private companion object {
        const val TAG = "GraphUpload"

        /** Graph's one-shot upload ceiling (~4 MB); larger content must use a chunked session. */
        const val LARGE_ATTACHMENT_THRESHOLD_BYTES = 4L * 1024 * 1024

        /** Every non-final upload chunk must be a multiple of 320 KiB, per Graph. */
        const val CHUNK_MULTIPLE_BYTES = 320 * 1024

        /** Default ~4.7 MB chunk (a 320 KiB multiple), inside Graph's recommended 5–10 MiB range. */
        const val DEFAULT_CHUNK_SIZE_BYTES = CHUNK_MULTIPLE_BYTES * 15

        const val HEADER_CONTENT_TYPE = "Content-Type"
        const val HEADER_CONTENT_RANGE = "Content-Range"
        const val CONTENT_TYPE_JSON = "application/json"
    }
}
