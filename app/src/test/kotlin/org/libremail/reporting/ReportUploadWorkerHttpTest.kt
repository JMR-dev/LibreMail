// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.reporting

import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals

/**
 * Covers [ReportUploadWorker]'s transmit path — unreachable while the default build ships an empty
 * `DEBUG_REPORT_ENDPOINT` — by injecting a non-empty endpoint (issue #257). The endpoint points at an
 * in-process [HttpServer] on loopback (JDK built-in, so no new dependency, and the same "real
 * in-process server" approach the suite already uses with GreenMail for IMAP/SMTP), so the actual
 * `HttpURLConnection` POST, response-code handling, retry/backoff decision, and on-success delete run
 * end to end:
 *  - 2xx -> success, and the delivered report is dropped from the local store;
 *  - 4xx -> permanent failure (retrying a client error can't help), store untouched;
 *  - 5xx -> retry while attempts remain, then failure once the attempt cap is hit;
 *  - a network error (nothing listening) -> retry while attempts remain.
 * It also pins the request the server receives: a JSON POST whose body is the report's submission
 * payload — the exact wire format the (out-of-scope) ingest server would get.
 */
class ReportUploadWorkerHttpTest {

    private val store = mockk<ReportStore>(relaxed = true)
    private lateinit var server: HttpServer
    private val responseCode = AtomicInteger(HTTP_OK)
    private val receivedBody = AtomicReference<String>()
    private val receivedContentType = AtomicReference<String>()
    private lateinit var endpoint: String

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(LOOPBACK, 0), 0)
        server.createContext(PATH) { exchange ->
            receivedContentType.set(exchange.requestHeaders.getFirst("Content-Type"))
            receivedBody.set(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            exchange.sendResponseHeaders(responseCode.get(), NO_RESPONSE_BODY)
            exchange.close()
        }
        server.start()
        endpoint = "http://$LOOPBACK:${server.address.port}$PATH"
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun `a 2xx response succeeds and drops the delivered report`() = runTest {
        val report = report()
        every { store.find(REPORT_ID) } returns report
        responseCode.set(HTTP_OK)

        val result = worker(endpoint).doWork()

        assertEquals(Result.success(), result)
        verify { store.delete(REPORT_ID) }
        assertEquals(report.toSubmissionPayload(), receivedBody.get())
        assertEquals("application/json; charset=utf-8", receivedContentType.get())
    }

    @Test
    fun `a 4xx client error fails permanently without deleting the report`() = runTest {
        every { store.find(REPORT_ID) } returns report()
        responseCode.set(HTTP_BAD_REQUEST)

        val result = worker(endpoint).doWork()

        assertEquals(Result.failure(), result)
        verify(exactly = 0) { store.delete(any()) }
    }

    @Test
    fun `a 5xx server error retries while attempts remain`() = runTest {
        every { store.find(REPORT_ID) } returns report()
        responseCode.set(HTTP_SERVER_ERROR)

        val result = worker(endpoint, attempt = 0).doWork()

        assertEquals(Result.retry(), result)
        verify(exactly = 0) { store.delete(any()) }
    }

    @Test
    fun `a 5xx server error fails once the attempt cap is hit`() = runTest {
        every { store.find(REPORT_ID) } returns report()
        responseCode.set(HTTP_SERVER_ERROR)

        val result = worker(endpoint, attempt = MAX_ATTEMPTS).doWork()

        assertEquals(Result.failure(), result)
    }

    @Test
    fun `a network error retries while attempts remain`() = runTest {
        every { store.find(REPORT_ID) } returns report()

        val result = worker(deadEndpoint(), attempt = 0).doWork()

        assertEquals(Result.retry(), result)
        verify(exactly = 0) { store.delete(any()) }
    }

    private fun worker(endpoint: String, attempt: Int = 0) = ReportUploadWorker(
        mockk(relaxed = true),
        mockk<WorkerParameters>(relaxed = true) {
            every { inputData } returns workDataOf(ReportUploadWorker.KEY_REPORT_ID to REPORT_ID)
            every { runAttemptCount } returns attempt
        },
        store,
        endpoint = endpoint,
    )

    /** A loopback URL with nothing listening: claim then immediately free a port so the POST is refused. */
    private fun deadEndpoint(): String {
        val port = ServerSocket(0, 0, InetAddress.getByName(LOOPBACK)).use { it.localPort }
        return "http://$LOOPBACK:$port$PATH"
    }

    private fun report() = DebugReport(
        id = REPORT_ID,
        createdAtMillis = 1L,
        kind = ReportKind.CRASH,
        appVersionName = "0.1.0",
        appVersionCode = 1,
        androidRelease = "14",
        androidSdkInt = 34,
        deviceManufacturer = "Google",
        deviceModel = "Pixel",
        stackTrace = "boom",
        settings = emptyMap(),
        logs = emptyList(),
    )

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val PATH = "/report"
        const val REPORT_ID = "rid"
        const val NO_RESPONSE_BODY = -1L
        const val HTTP_OK = 200
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_SERVER_ERROR = 500

        // Mirrors ReportUploadWorker.MAX_ATTEMPTS (private): at/after this count a retry becomes a failure.
        const val MAX_ATTEMPTS = 5
    }
}
