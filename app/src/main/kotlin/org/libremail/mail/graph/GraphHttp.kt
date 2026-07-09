// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail.graph

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/** Base of the HTTP success range (200), reused across the Graph layer. */
internal const val HTTP_SUCCESS_MIN = 200

/** Top of the HTTP success range (299), reused across the Graph layer. */
internal const val HTTP_SUCCESS_MAX = 299

/** True for any 2xx status — the shared "the request was accepted" predicate for the Graph layer. */
internal fun isHttpSuccess(status: Int): Boolean = status in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX

/**
 * A single Microsoft Graph HTTP request. [url] is absolute for a top-level call (`me/sendMail`, the
 * `$batch` endpoint, an upload-session URL) and [body], when non-null, is the raw bytes to transmit —
 * JSON for most calls, a binary slice for an upload-session chunk. [headers] carries the bearer
 * `Authorization` plus any per-call header (`Content-Type`, `Content-Range`).
 *
 * Intentionally a plain class, not a `data class`: it carries a [ByteArray] (value-equality would be a
 * footgun and detekt's `ArrayInDataClass` forbids it) and is never compared or destructured — callers
 * only read its properties.
 */
class GraphRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
)

/**
 * A completed Graph HTTP response: the [status] code, the decoded [body] text, and — when the server
 * sent a `Retry-After` header — the parsed minimum wait in [retryAfterMillis]. A response object means
 * the server answered (any status, including 429/503); a request that never got an answer surfaces as
 * a [GraphTransportException] instead so the caller can reason about whether it may already have sent.
 */
data class GraphResponse(val status: Int, val body: String, val retryAfterMillis: Long? = null)

/**
 * Thrown when a Graph request produced **no HTTP response** at all. [mayHaveSent] is true only when the
 * request was fully transmitted but the response could not be read — the server may already have acted
 * on it, so a `sendMail` caller must NOT blindly retry or fall back (it could duplicate the message).
 * A failure to even transmit the body sets it false (safe to retry / fall back).
 */
class GraphTransportException(message: String, val mayHaveSent: Boolean, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * The single Microsoft Graph HTTP transport seam. Deliberately thin: it opens one [HttpURLConnection],
 * writes the body, reads the status + body + `Retry-After`, and always disconnects — it does **no**
 * retrying, backoff, or throttle bookkeeping (that is [GraphThrottle]'s job, layered on top so every
 * Graph caller — send, `$batch`, chunked upload — shares one throttle policy).
 *
 * `open` with an injectable no-arg constructor so unit/instrumented tests substitute an in-memory fake
 * (no network, no process-wide URL handler) while production talks to `graph.microsoft.com`.
 */
@Singleton
open class GraphHttpClient @Inject constructor() {

    /**
     * Executes [request], returning the server's [GraphResponse] for **any** status it answered with.
     * Throws [GraphTransportException] when there was no response: [GraphTransportException.mayHaveSent]
     * distinguishes a body that never left the device (false) from one fully sent but whose response was
     * lost (true), preserving the send path's no-duplicate guarantee.
     */
    open suspend fun execute(request: GraphRequest): GraphResponse = withContext(Dispatchers.IO) {
        val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
            requestMethod = request.method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            request.headers.forEach { (name, value) -> setRequestProperty(name, value) }
            if (request.body != null) doOutput = true
        }
        try {
            writeBody(connection, request.body)
            val status = readStatus(connection)
            val stream = if (isHttpSuccess(status)) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val retryAfterHeader = connection.getHeaderField(HEADER_RETRY_AFTER)
            val retryAfter = parseRetryAfterMillis(retryAfterHeader, System.currentTimeMillis())
            GraphResponse(status = status, body = body, retryAfterMillis = retryAfter)
        } finally {
            connection.disconnect()
        }
    }

    /** Writes the request body, mapping a transmit failure to a safe-to-retry [GraphTransportException]. */
    private fun writeBody(connection: HttpURLConnection, body: ByteArray?) {
        if (body == null) return
        try {
            connection.outputStream.use { it.write(body) }
        } catch (e: IOException) {
            throw GraphTransportException("Graph request could not be transmitted", mayHaveSent = false, cause = e)
        }
    }

    /** Reads the status code, mapping a lost response to a maybe-sent [GraphTransportException]. */
    private fun readStatus(connection: HttpURLConnection): Int = try {
        connection.responseCode
    } catch (e: IOException) {
        throw GraphTransportException("Graph request sent but no response received", mayHaveSent = true, cause = e)
    }

    private companion object {
        const val TIMEOUT_MS = 15_000
        const val HEADER_RETRY_AFTER = "Retry-After"
    }
}

/** Milliseconds in one second — the unit of a numeric `Retry-After` (delta-seconds) header. */
private const val MILLIS_PER_SECOND = 1000L

/**
 * Parses an HTTP `Retry-After` header ([headerValue]) into a non-negative millisecond wait, or null
 * when it is absent/unparseable. Graph normally sends the RFC 7231 delta-seconds form (an integer
 * count of seconds); the HTTP-date form is also accepted and converted to a wait relative to
 * [nowMillis]. A past date (or a negative/garbage value) clamps to a valid wait rather than going
 * negative. Pure and clock-injected so it is deterministically unit-testable.
 */
internal fun parseRetryAfterMillis(headerValue: String?, nowMillis: Long): Long? {
    val trimmed = headerValue?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    trimmed.toLongOrNull()?.let { seconds -> return (seconds * MILLIS_PER_SECOND).coerceAtLeast(0L) }
    return runCatching {
        val instant = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        (instant.toEpochMilli() - nowMillis).coerceAtLeast(0L)
    }.getOrNull()
}
