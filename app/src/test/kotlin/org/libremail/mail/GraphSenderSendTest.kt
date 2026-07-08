// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.domain.model.OutgoingMessage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
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
 * Exercises the [GraphSender.send] transport path (its JSON payload builder is unit-tested separately
 * in [GraphSenderTest]). The Graph endpoint is a fixed https URL the sender news up itself, so the
 * test routes https through a process-wide [URLStreamHandlerFactory] to a per-test fake connection —
 * no network, no production seam. The behaviour pinned: a 2xx succeeds; a non-2xx is a safe-to-retry
 * rejection; a lost response is flagged [GraphSendException.mayHaveSent] (must NOT retry/fall back);
 * a transmit failure is not; and the connection is always disconnected.
 */
class GraphSenderSendTest {

    @Before
    fun setUp() {
        // send() now breadcrumbs through AppLog on the oversized-attachment guard; android.util.Log is a
        // no-op stub under plain JVM tests, so mock it (fully qualified, so this file never imports it).
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        armed.set(null)
        unmockkAll()
    }

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
    fun `an oversized attachment fails safe-to-fall-back before opening a connection`() = runTest {
        val last = arm() // armed, but the guard must trip before any connection is opened
        val big = File.createTempFile("graph-big", ".bin")
        try {
            // 4 MiB, over the 3 MiB per-file cap. setLength allocates the size without writing the bytes,
            // so the guard (which reads file.length()) trips without the test materializing 4 MiB.
            RandomAccessFile(big, "rw").use { it.setLength(4L * 1024 * 1024) }

            val ex = assertFailsWith<GraphSendException> {
                GraphSender().send("token", message, listOf(SendableAttachment(big)))
            }

            assertFalse(ex.mayHaveSent, "oversized never reached Graph, so SMTP fallback is safe")
            assertNull(last.get(), "the guard must trip before any connection is opened")
        } finally {
            big.delete()
        }
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
