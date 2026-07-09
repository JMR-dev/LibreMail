// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail.graph

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.data.sync.AccountThrottleGate

/**
 * Exercises the issue #364 Graph throttling toolkit on a real device/emulator (the CI E2E matrix is
 * authoritative for this): the `$batch` call-volume reduction, the chunked upload session, and the 429
 * composition with the shared #360 [AccountThrottleGate] all run under the Android runtime and its real
 * `android.util.Log` (no JVM stub to mock). Deliberately avoids the retry-delay path so it needs no
 * coroutines-test virtual clock (unavailable on the androidTest classpath) — the delay/backoff schedule
 * is proven under virtual time in the JVM suite (GraphThrottleTest).
 */
@RunWith(AndroidJUnit4::class)
class GraphThrottleInstrumentedTest {

    private val accountId = "outlook:user@example.org"

    /** A no-network [GraphHttpClient] that records requests and returns scripted responses. */
    private class FakeClient(private val responder: (GraphRequest, Int) -> GraphResponse) : GraphHttpClient() {
        val requests = mutableListOf<GraphRequest>()
        override suspend fun execute(request: GraphRequest): GraphResponse {
            val index = requests.size
            requests += request
            return responder(request, index)
        }
    }

    @Test
    fun batch_collapses_many_operations_into_few_calls() = runBlocking {
        val client = FakeClient { request, _ ->
            val requested = JSONObject(String(request.body!!, Charsets.UTF_8)).getJSONArray("requests")
            val responses = JSONArray()
            for (i in 0 until requested.length()) {
                responses.put(JSONObject().put("id", requested.getJSONObject(i).getString("id")).put("status", 200))
            }
            GraphResponse(status = 200, body = JSONObject().put("responses", responses).toString())
        }
        val requests = (1..25).map { GraphSubRequest(id = it.toString(), method = "GET", url = "/me/messages/$it") }

        val responses = GraphBatch(GraphThrottle(AccountThrottleGate())).execute(accountId, client, requests)

        assertEquals(2, client.requests.size)
        assertEquals(25, responses.size)
    }

    @Test
    fun upload_session_uploads_over_threshold_content_in_chunks() = runBlocking {
        val chunk = 320 * 1024
        val client = FakeClient { request, _ ->
            if (request.method == "POST") {
                GraphResponse(200, JSONObject().put("uploadUrl", "https://upload.example/1").toString())
            } else {
                GraphResponse(202, "")
            }
        }
        val total = 4 * 1024 * 1024 + 500
        val content = ByteArray(total) { (it % 251).toByte() }
        val session = GraphUploadSession(GraphThrottle(AccountThrottleGate()))
        assertTrue(session.requiresUploadSession(total.toLong()))

        session.upload(accountId, client, "https://graph/createUploadSession", JSONObject(), content, chunk)

        val puts = client.requests.drop(1)
        assertEquals((total + chunk - 1) / chunk, puts.size)
        assertTrue(puts.all { it.method == "PUT" })
    }

    @Test
    fun a_429_records_and_a_success_clears_the_shared_gate() = runBlocking {
        val gate = AccountThrottleGate()
        val throttle = GraphThrottle(gate)

        // maxRetries = 0 → no backoff delay; the 429 is recorded and returned.
        val client429 = FakeClient { _, _ -> GraphResponse(429, "") }
        val throttled = throttle.execute(accountId, client429, sendMail(), maxRetries = 0)
        assertEquals(429, throttled.status)
        assertTrue("an unrecovered 429 backs the account off", gate.isThrottled(accountId))

        val ok = throttle.execute(accountId, FakeClient { _, _ -> GraphResponse(202, "") }, sendMail())
        assertEquals(202, ok.status)
        assertFalse("a later success clears the backoff", gate.isThrottled(accountId))
    }

    private fun sendMail() = GraphRequest("POST", "https://graph.microsoft.com/v1.0/me/sendMail")
}
