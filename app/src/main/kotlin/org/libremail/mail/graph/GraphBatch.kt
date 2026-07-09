// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail.graph

import org.json.JSONArray
import org.json.JSONObject
import org.libremail.reporting.AppLog
import org.libremail.reporting.accountLogRef
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One request inside a Graph `$batch`. [url] is **relative** to the service root (e.g.
 * `/me/messages/{id}`), as the batch envelope requires. [body] is the optional JSON payload for a
 * write sub-request; [headers] carries any per-op header the sub-request needs.
 */
data class GraphSubRequest(
    val id: String,
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: JSONObject? = null,
)

/**
 * The result of one `$batch` sub-request, correlated back to its [GraphSubRequest.id]. [status] is the
 * sub-response's own HTTP status (a batch call can return 200 overall while an individual op is 429),
 * [body] its JSON payload if any, and [retryAfterMillis] the parsed per-op `Retry-After` when throttled.
 */
data class GraphSubResponse(
    val id: String,
    val status: Int,
    val body: JSONObject? = null,
    val retryAfterMillis: Long? = null,
)

/**
 * Multiplexes many Microsoft Graph reads/writes through the `$batch` endpoint (issue #364) so N calls
 * collapse to `ceil(N / 20)` HTTP round-trips — the documented cap is 20 operations per batch. This is
 * the lever for the 10,000-requests-per-10-minutes window: a backfill page that would otherwise fan out
 * one request per message becomes a single batch, staying far under the budget and the 4-concurrent cap.
 *
 * Every batch call runs through [GraphThrottle], so the envelope shares the app-wide Graph throttle
 * policy; a 429 surfaced **inside** the envelope (per-op) is fed back to the shared account backoff too.
 * Logging is PII-free (account ref, counts, statuses only).
 */
@Singleton
class GraphBatch @Inject constructor(private val throttle: GraphThrottle) {

    /**
     * Executes [requests] for [accountId] via [client], chunked into batches of at most
     * [MAX_BATCH_OPERATIONS]. Returns every sub-response, correlated by id and preserving request order.
     * Empty in, empty out (no HTTP call).
     */
    suspend fun execute(
        accountId: String,
        client: GraphHttpClient,
        requests: List<GraphSubRequest>,
    ): List<GraphSubResponse> {
        if (requests.isEmpty()) return emptyList()
        val chunks = requests.chunked(MAX_BATCH_OPERATIONS)
        val responses = ArrayList<GraphSubResponse>(requests.size)
        for (chunk in chunks) {
            val request = GraphRequest(
                method = "POST",
                url = BATCH_URL,
                headers = mapOf(HEADER_CONTENT_TYPE to CONTENT_TYPE_JSON),
                body = buildBatchPayload(chunk).toByteArray(Charsets.UTF_8),
            )
            val parsed = parseBatchResponses(throttle.execute(accountId, client, request).body)
            parsed.forEach { sub ->
                if (!isHttpSuccess(sub.status)) {
                    throttle.recordSubResponseThrottle(accountId, sub.status, sub.retryAfterMillis)
                }
            }
            responses += parsed
        }
        AppLog.d(
            TAG,
            "graph batch ${accountLogRef(accountId)} ops=${requests.size} calls=${chunks.size}",
        )
        return responses
    }

    private companion object {
        const val TAG = "GraphBatch"

        /** Graph accepts at most 20 operations per `$batch` request. */
        const val MAX_BATCH_OPERATIONS = 20

        const val BATCH_URL = "https://graph.microsoft.com/v1.0/\$batch"
        const val HEADER_CONTENT_TYPE = "Content-Type"
        const val CONTENT_TYPE_JSON = "application/json"
    }
}

/** Builds the `{"requests":[...]}` JSON body for a chunk of sub-requests (pure, so it is unit-testable). */
internal fun buildBatchPayload(requests: List<GraphSubRequest>): String {
    val array = JSONArray()
    requests.forEach { request ->
        val obj = JSONObject()
            .put("id", request.id)
            .put("method", request.method)
            .put("url", request.url)
        if (request.headers.isNotEmpty()) {
            val headers = JSONObject()
            request.headers.forEach { (name, value) -> headers.put(name, value) }
            obj.put("headers", headers)
        }
        request.body?.let { obj.put("body", it) }
        array.put(obj)
    }
    return JSONObject().put("requests", array).toString()
}

/**
 * Parses a `$batch` response body's `{"responses":[...]}` array into [GraphSubResponse]s (pure, so it is
 * unit-testable). A per-op `Retry-After` header (case-insensitive) is read into
 * [GraphSubResponse.retryAfterMillis]. A malformed/empty body yields an empty list rather than throwing.
 */
internal fun parseBatchResponses(body: String): List<GraphSubResponse> {
    if (body.isBlank()) return emptyList()
    val responses = runCatching { JSONObject(body).optJSONArray("responses") }.getOrNull() ?: return emptyList()
    val out = ArrayList<GraphSubResponse>(responses.length())
    for (index in 0 until responses.length()) {
        val item = responses.optJSONObject(index) ?: continue
        out += GraphSubResponse(
            id = item.optString("id"),
            status = item.optInt("status"),
            body = item.optJSONObject("body"),
            retryAfterMillis = subResponseRetryAfterMillis(item),
        )
    }
    return out
}

/** Reads a case-insensitive `Retry-After` from a sub-response's `headers` object, parsed to millis. */
private fun subResponseRetryAfterMillis(item: JSONObject): Long? {
    val headers = item.optJSONObject("headers") ?: return null
    val key = headers.keys().asSequence().firstOrNull { it.equals("Retry-After", ignoreCase = true) } ?: return null
    return parseRetryAfterMillis(headers.optString(key), System.currentTimeMillis())
}
