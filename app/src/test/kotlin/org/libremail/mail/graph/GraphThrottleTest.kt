// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail.graph

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.data.sync.AccountThrottleGate
import org.libremail.data.sync.ThrottleKind
import org.libremail.data.sync.ThrottleSignal
import org.libremail.reporting.AppLog
import org.libremail.reporting.RingLogBuffer
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [GraphThrottle] must honor a Graph 429/503 (retry after the server's `Retry-After`, composed with the
 * shared #360 backoff gate), bound its retries, clear the gate on success, leave a non-throttle error
 * untouched, not retry a transport failure, and log only PII-free breadcrumbs — all under coroutines-test
 * virtual time, with no real sleeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GraphThrottleTest {

    private val logBuffer = RingLogBuffer()
    private val accountId = "outlook:user@example.org"

    @Before
    fun setUp() {
        // GraphThrottle (and the gate it drives) log via AppLog → android.util.Log, a throwing no-op stub
        // under plain JVM tests; mock it fully-qualified so this file never imports android.util.Log.
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        AppLog.install(logBuffer)
    }

    @After
    fun tearDown() = unmockkAll()

    private fun ok(body: String = "") = GraphResponse(status = 202, body = body)
    private fun throttled(retryAfterMillis: Long?) =
        GraphResponse(status = 429, body = "", retryAfterMillis = retryAfterMillis)
    private fun request() = GraphRequest("POST", "https://graph.microsoft.com/v1.0/me/sendMail")

    @Test
    fun `a 429 with Retry-After is honored then the retry succeeds`() = runTest {
        val gate = AccountThrottleGate(nowMillis = { testScheduler.currentTime }, random = { 0.0 })
        val throttle = GraphThrottle(gate)
        // Retry-After 120s exceeds the base exponential half (15s), so the honored wait is exactly 120s.
        val client = FakeGraphHttpClient.sequence(throttled(retryAfterMillis = 120_000L), ok(body = "sent"))

        val response = throttle.execute(accountId, client, request())

        assertEquals(202, response.status)
        assertEquals("sent", response.body)
        assertEquals(2, client.callCount, "it must retry exactly once after the 429")
        assertEquals(120_000L, testScheduler.currentTime, "it must wait the server's Retry-After before retrying")
        assertFalse(gate.isThrottled(accountId), "a successful retry clears the account's backoff")
    }

    @Test
    fun `a 429 records the account against the shared backoff gate`() = runTest {
        val gate = AccountThrottleGate(nowMillis = { testScheduler.currentTime }, random = { 0.0 })
        val client = FakeGraphHttpClient.always(throttled(null))

        val response = GraphThrottle(gate).execute(accountId, client, request(), maxRetries = 0)

        assertEquals(429, response.status)
        assertTrue(gate.isThrottled(accountId), "an unrecovered 429 leaves the account backed off for other paths")
    }

    @Test
    fun `retries are bounded and the throttled response is finally returned`() = runTest {
        val gate = AccountThrottleGate(nowMillis = { testScheduler.currentTime }, random = { 0.0 })
        val client = FakeGraphHttpClient.always(throttled(retryAfterMillis = 1_000L))

        val response = GraphThrottle(gate).execute(accountId, client, request(), maxRetries = 2)

        assertEquals(429, response.status)
        assertEquals(3, client.callCount, "one initial attempt plus two bounded retries")
    }

    @Test
    fun `a success clears a pre-existing backoff`() = runTest {
        val gate = AccountThrottleGate(nowMillis = { testScheduler.currentTime }, random = { 0.0 })
        gate.onThrottle(accountId, ThrottleSignal(ThrottleKind.RATE_LIMIT))
        assertTrue(gate.isThrottled(accountId))

        GraphThrottle(gate).execute(accountId, FakeGraphHttpClient.always(ok()), request())

        assertFalse(gate.isThrottled(accountId), "a 2xx clears the account's backoff")
    }

    @Test
    fun `a non-throttle error is returned as-is and does not clear backoff`() = runTest {
        val gate = AccountThrottleGate(nowMillis = { testScheduler.currentTime }, random = { 0.0 })
        gate.onThrottle(accountId, ThrottleSignal(ThrottleKind.RATE_LIMIT))
        val client = FakeGraphHttpClient.always(GraphResponse(status = 401, body = "unauthorized"))

        val response = GraphThrottle(gate).execute(accountId, client, request())

        assertEquals(401, response.status)
        assertEquals(1, client.callCount, "a 4xx that is not a throttle must not be retried")
        assertTrue(gate.isThrottled(accountId), "a non-2xx must not clear an existing backoff")
    }

    @Test
    fun `a transport failure propagates without a retry`() = runTest {
        val gate = AccountThrottleGate(nowMillis = { testScheduler.currentTime }, random = { 0.0 })
        val client = FakeGraphHttpClient { _, _ -> throw GraphTransportException("lost", mayHaveSent = true) }

        assertFailsWith<GraphTransportException> {
            GraphThrottle(gate).execute(accountId, client, request())
        }
        assertEquals(1, client.callCount, "a no-response transport error is the caller's to judge, never retried here")
    }

    @Test
    fun `throttle breadcrumbs are PII-free`() = runTest {
        val gate = AccountThrottleGate(nowMillis = { testScheduler.currentTime }, random = { 0.0 })

        GraphThrottle(gate).execute(accountId, FakeGraphHttpClient.always(throttled(null)), request(), maxRetries = 0)

        val messages = logBuffer.snapshot().map { it.message }
        assertTrue(messages.any { it.startsWith("graph throttled outlook:") }, messages.toString())
        messages.forEach { assertFalse(it.contains("user@example.org"), it) }
    }
}
