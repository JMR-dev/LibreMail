// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail.graph

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.data.sync.AccountThrottleGate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [GraphBatch] must collapse many operations into `ceil(N / 20)` `$batch` HTTP calls (the core
 * call-volume lever of issue #364), correlate every sub-response back to its request id, make no call
 * for an empty list, and feed a per-op 429 back into the shared #360 backoff gate. Plus pure coverage
 * of the payload builder and response parser.
 */
class GraphBatchTest {

    private val accountId = "outlook:user@example.org"

    @Before
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
    }

    @After
    fun tearDown() = unmockkAll()

    private fun gate() = AccountThrottleGate(nowMillis = { 0L }, random = { 0.0 })

    /** A fake that echoes one 200 sub-response per sub-request in the batch payload it receives. */
    private fun echoClient() = FakeGraphHttpClient { request, _ ->
        val requested = JSONObject(String(request.body!!, Charsets.UTF_8)).getJSONArray("requests")
        val responses = JSONArray()
        for (index in 0 until requested.length()) {
            responses.put(JSONObject().put("id", requested.getJSONObject(index).getString("id")).put("status", 200))
        }
        GraphResponse(status = 200, body = JSONObject().put("responses", responses).toString())
    }

    private fun subRequests(count: Int) =
        (1..count).map { GraphSubRequest(id = it.toString(), method = "GET", url = "/me/messages/$it") }

    @Test
    fun `twenty-five operations collapse to two batch calls`() = runTest {
        val client = echoClient()

        val responses = GraphBatch(GraphThrottle(gate())).execute(accountId, client, subRequests(25))

        assertEquals(2, client.callCount, "25 ops / 20-per-batch = 2 HTTP round-trips instead of 25")
        assertEquals(25, responses.size)
        assertEquals((1..25).map { it.toString() }, responses.map { it.id }, "order + id correlation preserved")
        assertTrue(responses.all { it.status == 200 })
    }

    @Test
    fun `exactly twenty operations are a single batch call`() = runTest {
        val client = echoClient()

        GraphBatch(GraphThrottle(gate())).execute(accountId, client, subRequests(20))

        assertEquals(1, client.callCount)
    }

    @Test
    fun `an empty operation list makes no HTTP call`() = runTest {
        val client = echoClient()

        val responses = GraphBatch(GraphThrottle(gate())).execute(accountId, client, emptyList())

        assertTrue(responses.isEmpty())
        assertEquals(0, client.callCount)
    }

    @Test
    fun `a per-op 429 inside the envelope backs the account off`() = runTest {
        val gate = gate()
        val client = FakeGraphHttpClient.always(
            GraphResponse(
                status = 200,
                body = JSONObject().put(
                    "responses",
                    JSONArray().put(
                        JSONObject().put("id", "1").put("status", 429)
                            .put("headers", JSONObject().put("Retry-After", "60")),
                    ),
                ).toString(),
            ),
        )

        val responses = GraphBatch(GraphThrottle(gate)).execute(accountId, client, subRequests(1))

        assertEquals(429, responses.single().status)
        assertEquals(60_000L, responses.single().retryAfterMillis)
        assertTrue(gate.isThrottled(accountId), "a throttled sub-response cools the account down like a top-level 429")
    }

    @Test
    fun `buildBatchPayload carries id, method, url, headers and body`() {
        val payload = JSONObject(
            buildBatchPayload(
                listOf(
                    GraphSubRequest(
                        id = "a",
                        method = "POST",
                        url = "/me/sendMail",
                        headers = mapOf("Content-Type" to "application/json"),
                        body = JSONObject().put("saveToSentItems", true),
                    ),
                ),
            ),
        )
        val request = payload.getJSONArray("requests").getJSONObject(0)
        assertEquals("a", request.getString("id"))
        assertEquals("POST", request.getString("method"))
        assertEquals("/me/sendMail", request.getString("url"))
        assertEquals("application/json", request.getJSONObject("headers").getString("Content-Type"))
        assertTrue(request.getJSONObject("body").getBoolean("saveToSentItems"))
    }

    @Test
    fun `parseBatchResponses correlates id, status and body`() {
        val parsed = parseBatchResponses(
            JSONObject().put(
                "responses",
                JSONArray()
                    .put(JSONObject().put("id", "1").put("status", 200).put("body", JSONObject().put("ok", true)))
                    .put(JSONObject().put("id", "2").put("status", 404)),
            ).toString(),
        )
        assertEquals(listOf("1", "2"), parsed.map { it.id })
        assertEquals(200, parsed[0].status)
        assertTrue(parsed[0].body!!.getBoolean("ok"))
        assertEquals(404, parsed[1].status)
    }

    @Test
    fun `parseBatchResponses tolerates a blank or malformed body`() {
        assertTrue(parseBatchResponses("").isEmpty())
        assertTrue(parseBatchResponses("not json").isEmpty())
        assertTrue(parseBatchResponses("{}").isEmpty())
    }
}
