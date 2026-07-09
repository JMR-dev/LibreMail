// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail.graph

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.libremail.data.sync.AccountThrottleGate
import org.libremail.data.sync.ThrottleClassifier
import org.libremail.reporting.AppLog
import org.libremail.reporting.accountLogRef
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Microsoft Graph throttle policy layered over the raw [GraphHttpClient] transport — the Graph-side
 * embodiment of issue #364, composed with the shared reactive backoff of issue #360
 * ([AccountThrottleGate]). Every Graph call — `me/sendMail`, `$batch`, an upload-session chunk — runs
 * through [execute], so all of them share one throttle policy:
 *
 * - **Concurrency cap.** Graph rejects a mailbox's 5th concurrent request with a 429, so at most
 *   [MAX_CONCURRENT_REQUESTS] Graph calls run at once (a [Semaphore]); the rest queue rather than
 *   provoke the limit. Held only around the network round-trip, released before any backoff sleep.
 * - **Honor `Retry-After` on 429/503.** A throttled response is classified via
 *   [ThrottleClassifier.classifyHttpStatus] and recorded against the account in the shared gate, whose
 *   backoff honors the server's `Retry-After` as a floor. The call then waits that long and retries, up
 *   to [maxRetries] extra attempts, before surfacing the last throttled response to the caller.
 * - **Cross-path cooperation.** Because the gate is keyed by account id and shared with the IMAP
 *   backfill/sync paths (#360), a Graph 429 also cools that account's background IMAP work down, and a
 *   clean Graph response clears any lingering backoff — the send and receive paths never fight the same
 *   provider limit from two directions.
 *
 * All logging is PII-free ([accountLogRef], statuses, and durations only).
 */
@Singleton
class GraphThrottle @Inject constructor(private val throttleGate: AccountThrottleGate) {

    /** At most four Graph requests in flight at once — Graph 429s the 5th concurrent request per mailbox. */
    private val concurrency = Semaphore(MAX_CONCURRENT_REQUESTS)

    /**
     * Runs [request] through [client] for [accountId], honoring Graph throttling. A 429/503 is recorded
     * against the account's shared backoff gate and retried after the honored wait, up to [maxRetries]
     * additional attempts; a 2xx clears any backoff. Returns the final [GraphResponse] — including a
     * still-throttled one once retries are exhausted — so the caller maps it to its own result.
     * [GraphTransportException] (no response at all) propagates unretried: the caller owns the
     * may-have-sent decision.
     */
    suspend fun execute(
        accountId: String,
        client: GraphHttpClient,
        request: GraphRequest,
        maxRetries: Int = DEFAULT_MAX_RETRIES,
    ): GraphResponse {
        var retries = 0
        while (true) {
            val response = concurrency.withPermit { client.execute(request) }
            val signal = ThrottleClassifier.classifyHttpStatus(response.status, response.retryAfterMillis)
            if (signal == null) {
                if (isHttpSuccess(response.status)) throttleGate.onSuccess(accountId)
                return response
            }
            val backoff = throttleGate.onThrottle(accountId, signal)
            if (retries >= maxRetries) {
                AppLog.w(
                    TAG,
                    "graph throttled ${accountLogRef(accountId)} status=${response.status} " +
                        "retries exhausted ($retries/$maxRetries)",
                )
                return response
            }
            retries++
            AppLog.w(
                TAG,
                "graph throttled ${accountLogRef(accountId)} status=${response.status} " +
                    "backoff=${backoff}ms retry=$retries/$maxRetries",
            )
            delay(backoff)
        }
    }

    /**
     * Records a throttle observed **inside** a `$batch` envelope — Graph answers the batch call 200 but
     * can mark individual sub-responses 429/503 with their own `Retry-After`. Feeds the shared gate so a
     * multiplexed read that hit the limit backs the account off exactly like a top-level 429 would.
     * Returns the resulting backoff in ms, or null when [status] was not a throttle.
     */
    fun recordSubResponseThrottle(accountId: String, status: Int, retryAfterMillis: Long?): Long? {
        val signal = ThrottleClassifier.classifyHttpStatus(status, retryAfterMillis) ?: return null
        return throttleGate.onThrottle(accountId, signal)
    }

    private companion object {
        const val TAG = "GraphThrottle"

        /** Graph caps a single mailbox at four concurrent requests before 429-ing the rest. */
        const val MAX_CONCURRENT_REQUESTS = 4

        /** Default extra attempts after the first, for background/multiplexed calls (send overrides lower). */
        const val DEFAULT_MAX_RETRIES = 2
    }
}
