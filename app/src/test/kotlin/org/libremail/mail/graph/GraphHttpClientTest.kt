// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail.graph

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.net.URLStreamHandlerFactory
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the real [GraphHttpClient] transport and the pure [parseRetryAfterMillis] helper. The Graph
 * endpoints are fixed https URLs the client opens itself, so — like the old GraphSender transport test —
 * https is routed through a process-wide [URLStreamHandlerFactory] to a per-test fake connection: no
 * network, no production seam. Pinned behaviour: any answered status (2xx or not) returns a
 * [GraphResponse] with its body and parsed `Retry-After`; a transmit failure and a lost response each
 * throw [GraphTransportException] with the right [GraphTransportException.mayHaveSent] flag; and the
 * connection is always disconnected.
 */
class GraphHttpClientTest {

    @After
    fun tearDown() {
        armed.set(null)
    }

    private fun arm(
        status: Int = 202,
        body: String = "",
        retryAfter: String? = null,
        failOutput: Boolean = false,
        failResponse: Boolean = false,
    ): AtomicReference<FakeConnection?> {
        val last = AtomicReference<FakeConnection?>(null)
        armed.set { u -> FakeConnection(u, status, body, retryAfter, failOutput, failResponse).also { last.set(it) } }
        return last
    }

    private fun request(body: ByteArray? = ByteArray(0)) =
        GraphRequest("POST", "https://graph.microsoft.com/v1.0/me/sendMail", mapOf("Authorization" to "Bearer t"), body)

    @Test
    fun `a 2xx response returns its status and body and disconnects`() = runTest {
        val last = arm(status = 200, body = "{\"ok\":true}")

        val response = GraphHttpClient().execute(request())

        assertEquals(200, response.status)
        assertEquals("{\"ok\":true}", response.body)
        assertTrue(last.get()!!.disconnected)
    }

    @Test
    fun `a non-2xx response returns its error body and disconnects`() = runTest {
        val last = arm(status = 400, body = "{\"error\":\"bad\"}")

        val response = GraphHttpClient().execute(request())

        assertEquals(400, response.status)
        assertEquals("{\"error\":\"bad\"}", response.body)
        assertTrue(last.get()!!.disconnected)
    }

    @Test
    fun `a Retry-After header is parsed into milliseconds`() = runTest {
        arm(status = 429, body = "", retryAfter = "30")

        val response = GraphHttpClient().execute(request())

        assertEquals(429, response.status)
        assertEquals(30_000L, response.retryAfterMillis)
    }

    @Test
    fun `no Retry-After header yields a null wait`() = runTest {
        arm(status = 429, body = "")

        assertNull(GraphHttpClient().execute(request()).retryAfterMillis)
    }

    @Test
    fun `a transmit failure is thrown as not-maybe-sent`() = runTest {
        val last = arm(failOutput = true)

        val ex = assertFailsWith<GraphTransportException> { GraphHttpClient().execute(request()) }

        assertFalse(ex.mayHaveSent, "the body never left the device, so a retry is safe")
        assertTrue(last.get()!!.disconnected)
    }

    @Test
    fun `a lost response is thrown as maybe-sent`() = runTest {
        arm(failResponse = true)

        val ex = assertFailsWith<GraphTransportException> { GraphHttpClient().execute(request()) }

        assertTrue(ex.mayHaveSent, "the request was fully sent, so it may already have been acted on")
    }

    @Test
    fun `a request without a body is not transmitted but still reads the response`() = runTest {
        val last = arm(status = 200, body = "ok")

        val response = GraphHttpClient().execute(request(body = null))

        assertEquals(200, response.status)
        assertFalse(last.get()!!.wroteBody, "a null body must not open the output stream")
    }

    // --- parseRetryAfterMillis (pure) -----------------------------------------------------------

    @Test
    fun `parseRetryAfterMillis reads delta-seconds`() {
        assertEquals(120_000L, parseRetryAfterMillis("120", nowMillis = 0L))
    }

    @Test
    fun `parseRetryAfterMillis reads an HTTP-date relative to now`() {
        // 60 seconds after the epoch, evaluated as of the epoch → 60_000 ms.
        assertEquals(60_000L, parseRetryAfterMillis("Thu, 01 Jan 1970 00:01:00 GMT", nowMillis = 0L))
    }

    @Test
    fun `parseRetryAfterMillis clamps a past date to zero`() {
        assertEquals(0L, parseRetryAfterMillis("Thu, 01 Jan 1970 00:00:00 GMT", nowMillis = 120_000L))
    }

    @Test
    fun `parseRetryAfterMillis returns null for blank or garbage`() {
        assertNull(parseRetryAfterMillis(null, 0L))
        assertNull(parseRetryAfterMillis("   ", 0L))
        assertNull(parseRetryAfterMillis("soon", 0L))
    }

    @Test
    fun `parseRetryAfterMillis clamps a negative delta to zero`() {
        assertEquals(0L, parseRetryAfterMillis("-5", 0L))
    }

    @Test
    fun `isHttpSuccess spans the 2xx range only`() {
        assertTrue(isHttpSuccess(200))
        assertTrue(isHttpSuccess(202))
        assertTrue(isHttpSuccess(299))
        assertFalse(isHttpSuccess(199))
        assertFalse(isHttpSuccess(300))
        assertFalse(isHttpSuccess(429))
    }

    private class FakeConnection(
        url: URL,
        private val status: Int,
        private val body: String,
        private val retryAfter: String?,
        private val failOutput: Boolean,
        private val failResponse: Boolean,
    ) : HttpURLConnection(url) {
        var disconnected = false
        var wroteBody = false
        override fun connect() = Unit
        override fun disconnect() {
            disconnected = true
        }
        override fun usingProxy() = false
        override fun getOutputStream(): OutputStream =
            if (failOutput) throw IOException("cannot transmit") else ByteArrayOutputStream().also { wroteBody = true }
        override fun getResponseCode(): Int = if (failResponse) throw IOException("no response") else status
        override fun getInputStream(): InputStream = body.byteInputStream()
        override fun getErrorStream(): InputStream? = if (body.isEmpty()) null else body.byteInputStream()
        override fun getHeaderField(name: String): String? =
            if (name.equals("Retry-After", ignoreCase = true)) retryAfter else super.getHeaderField(name)
    }

    companion object {
        private val armed = AtomicReference<((URL) -> HttpURLConnection)?>(null)

        // Set once per JVM: route https opens to whatever the running test armed. Only this test opens
        // real https in the unit-test JVM (every other Graph test uses FakeGraphHttpClient), so a
        // permanent https handler is safe here.
        init {
            URL.setURLStreamHandlerFactory(
                URLStreamHandlerFactory { protocol ->
                    if (protocol == "https") {
                        object : URLStreamHandler() {
                            override fun openConnection(u: URL): URLConnection =
                                armed.get()?.invoke(u) ?: throw IOException("no fake connection armed")
                        }
                    } else {
                        null
                    }
                },
            )
        }
    }
}
