// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.libremail.domain.model.OutgoingMessage
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
import kotlin.test.assertTrue

/**
 * Exercises the [GraphSender.send] transport path (its JSON payload builder is unit-tested separately
 * in [GraphSenderTest]). The Graph endpoint is a fixed https URL the sender news up itself, so the
 * test routes https through a process-wide [URLStreamHandlerFactory] to a per-test fake connection —
 * no network, no production seam. The behaviour pinned: a 2xx succeeds; a non-2xx is a safe-to-retry
 * rejection; a lost response is flagged [GraphSendException.mayHaveSent] (must NOT retry/fall back);
 * a transmit failure is not; and the connection is always disconnected.
 */
class GraphSenderSendTest {

    @After
    fun tearDown() = armed.set(null)

    private val message =
        OutgoingMessage(accountId = "outlook:me@x.com", to = "bob@example.org", subject = "Hi", body = "Body")

    private fun arm(
        status: Int = 202,
        body: String = "",
        failOutput: Boolean = false,
        failResponse: Boolean = false,
    ): AtomicReference<FakeGraphConnection?> {
        val last = AtomicReference<FakeGraphConnection?>(null)
        armed.set { u -> FakeGraphConnection(u, status, body, failOutput, failResponse).also { last.set(it) } }
        return last
    }

    @Test
    fun `a 2xx response completes the send`() = runTest {
        val last = arm(status = 202)

        GraphSender().send("token", message)

        assertTrue(last.get()!!.disconnected, "the connection must be disconnected when done")
    }

    @Test
    fun `a non-2xx response is a safe-to-retry rejection`() = runTest {
        arm(status = 400, body = "{\"error\":\"bad request\"}")

        val ex = assertFailsWith<GraphSendException> { GraphSender().send("token", message) }

        assertFalse(ex.mayHaveSent, "an explicit rejection means Graph did not send")
        assertTrue(ex.message!!.contains("HTTP 400"), ex.message!!)
    }

    @Test
    fun `a lost response is flagged as maybe-sent`() = runTest {
        arm(failResponse = true)

        val ex = assertFailsWith<GraphSendException> { GraphSender().send("token", message) }

        assertTrue(ex.mayHaveSent, "the request was fully sent, so it may already have delivered")
    }

    @Test
    fun `a transmit failure is not maybe-sent`() = runTest {
        arm(failOutput = true)

        val ex = assertFailsWith<GraphSendException> { GraphSender().send("token", message) }

        assertFalse(ex.mayHaveSent, "the request never reached Graph, so a retry is safe")
    }

    @Test
    fun `GraphSendException carries its message, flag and cause`() {
        val cause = IOException("boom")
        val ex = GraphSendException("failed", mayHaveSent = true, cause = cause)

        assertEquals("failed", ex.message)
        assertTrue(ex.mayHaveSent)
        assertEquals(cause, ex.cause)
    }

    private class FakeGraphConnection(
        url: URL,
        private val status: Int,
        private val body: String,
        private val failOutput: Boolean,
        private val failResponse: Boolean,
    ) : HttpURLConnection(url) {
        var disconnected = false
        override fun connect() = Unit
        override fun disconnect() {
            disconnected = true
        }
        override fun usingProxy() = false
        override fun getOutputStream(): OutputStream =
            if (failOutput) throw IOException("cannot transmit") else ByteArrayOutputStream()
        override fun getResponseCode(): Int = if (failResponse) throw IOException("no response") else status
        override fun getInputStream(): InputStream = body.byteInputStream()
        override fun getErrorStream(): InputStream? = if (body.isEmpty()) null else body.byteInputStream()
    }

    companion object {
        private val armed = AtomicReference<((URL) -> HttpURLConnection)?>(null)

        // Set once per JVM: route https opens to whatever the running test armed. Only this test opens
        // https in the unit-test JVM (ReportUploadWorker's endpoint is blank and never opened), so a
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
