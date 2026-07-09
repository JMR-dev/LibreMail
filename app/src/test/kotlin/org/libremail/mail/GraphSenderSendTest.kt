// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.data.sync.AccountThrottleGate
import org.libremail.domain.model.OutgoingMessage
import org.libremail.mail.graph.FakeGraphHttpClient
import org.libremail.mail.graph.GraphResponse
import org.libremail.mail.graph.GraphThrottle
import org.libremail.mail.graph.GraphTransportException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises [GraphSender.send] over a fake [org.libremail.mail.graph.GraphHttpClient] (its JSON payload
 * builder is unit-tested separately in [GraphSenderTest], and the raw transport in
 * [org.libremail.mail.graph.GraphHttpClientTest]). Pinned behaviour: a 2xx succeeds; a non-2xx is a
 * safe-to-retry rejection; a lost response is flagged [GraphSendException.mayHaveSent] (must NOT
 * retry/fall back); a transmit failure is not; an oversized attachment fails before any request; and a
 * 429 is honored via [GraphThrottle] (retried after Retry-After) rather than immediately failing over.
 */
class GraphSenderSendTest {

    @Before
    fun setUp() {
        // send() and the throttle/gate it composes with log via AppLog → android.util.Log, a throwing
        // no-op stub under plain JVM tests; mock it fully-qualified so this file never imports it.
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
    }

    @After
    fun tearDown() = unmockkAll()

    private val message =
        OutgoingMessage(accountId = "outlook:me@x.com", to = "bob@example.org", subject = "Hi", body = "Body")

    private fun sender(client: FakeGraphHttpClient): Pair<GraphSender, AccountThrottleGate> {
        val gate = AccountThrottleGate(nowMillis = { 0L }, random = { 0.0 })
        return GraphSender(client, GraphThrottle(gate)) to gate
    }

    @Test
    fun `a 2xx response completes the send`() = runTest {
        val client = FakeGraphHttpClient.always(GraphResponse(status = 202, body = ""))

        sender(client).first.send("token", message)

        assertEquals(1, client.callCount)
    }

    @Test
    fun `a non-2xx response is a safe-to-retry rejection`() = runTest {
        val client = FakeGraphHttpClient.always(GraphResponse(status = 400, body = "{\"error\":\"bad request\"}"))

        val ex = assertFailsWith<GraphSendException> { sender(client).first.send("token", message) }

        assertFalse(ex.mayHaveSent, "an explicit rejection means Graph did not send")
        assertTrue(ex.message!!.contains("HTTP 400"), ex.message!!)
    }

    @Test
    fun `a lost response is flagged as maybe-sent`() = runTest {
        val client = FakeGraphHttpClient { _, _ -> throw GraphTransportException("lost", mayHaveSent = true) }

        val ex = assertFailsWith<GraphSendException> { sender(client).first.send("token", message) }

        assertTrue(ex.mayHaveSent, "the request was fully sent, so it may already have delivered")
    }

    @Test
    fun `a transmit failure is not maybe-sent`() = runTest {
        val client = FakeGraphHttpClient { _, _ -> throw GraphTransportException("no transmit", mayHaveSent = false) }

        val ex = assertFailsWith<GraphSendException> { sender(client).first.send("token", message) }

        assertFalse(ex.mayHaveSent, "the request never reached Graph, so a retry is safe")
    }

    @Test
    fun `an oversized attachment fails safe-to-fall-back before opening a connection`() = runTest {
        val client = FakeGraphHttpClient.always(GraphResponse(status = 202, body = ""))
        val big = File.createTempFile("graph-big", ".bin")
        try {
            // 4 MiB, over the 3 MiB per-file cap. setLength allocates the size without writing the bytes,
            // so the guard (which reads file.length()) trips without the test materializing 4 MiB.
            RandomAccessFile(big, "rw").use { it.setLength(4L * 1024 * 1024) }

            val ex = assertFailsWith<GraphSendException> {
                sender(client).first.send("token", message, listOf(SendableAttachment(big)))
            }

            assertFalse(ex.mayHaveSent, "oversized never reached Graph, so SMTP fallback is safe")
            assertEquals(0, client.callCount, "the guard must trip before any request is made")
        } finally {
            big.delete()
        }
    }

    @Test
    fun `a 429 is retried after Retry-After then succeeds`() = runTest {
        val client = FakeGraphHttpClient.sequence(
            GraphResponse(status = 429, body = "", retryAfterMillis = 1_000L),
            GraphResponse(status = 202, body = ""),
        )

        sender(client).first.send("token", message)

        assertEquals(2, client.callCount, "the 429 is honored once, then the retry sends")
    }

    @Test
    fun `a persistent 429 surfaces as a safe-to-fall-back rejection`() = runTest {
        val client = FakeGraphHttpClient.always(GraphResponse(status = 429, body = "quota", retryAfterMillis = 1_000L))
        val (graphSender, gate) = sender(client)

        val ex = assertFailsWith<GraphSendException> { graphSender.send("token", message) }

        // One initial attempt + one honored retry (SEND_MAX_RETRIES = 1), then fall back.
        assertEquals(2, client.callCount)
        assertFalse(ex.mayHaveSent, "a 429 means Graph did not send, so SMTP fallback is safe")
        assertTrue(ex.message!!.contains("HTTP 429"), ex.message!!)
        // The throttle is recorded so the account's background IMAP work backs off too (#360 composition).
        assertTrue(gate.isThrottled(message.accountId))
    }

    @Test
    fun `GraphSendException carries its message, flag and cause`() {
        val cause = IOException("boom")
        val ex = GraphSendException("failed", mayHaveSent = true, cause = cause)

        assertEquals("failed", ex.message)
        assertTrue(ex.mayHaveSent)
        assertEquals(cause, ex.cause)
    }
}
