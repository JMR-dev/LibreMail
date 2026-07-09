// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail.graph

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.data.sync.AccountThrottleGate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [GraphUploadSession] must decide when a chunked session is required (the ~4 MB one-shot ceiling), then
 * create the session and PUT the content in contiguous 320 KiB-multiple ranges that reassemble to the
 * original bytes (issue #364's chunked-upload lever). It must also short-circuit a failed session-create
 * and reject invalid inputs.
 */
class GraphUploadSessionTest {

    private val accountId = "outlook:user@example.org"
    private val chunkMultiple = 320 * 1024
    private val createUrl = "https://graph.microsoft.com/v1.0/me/messages/1/attachments/createUploadSession"

    @Before
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
    }

    @After
    fun tearDown() = unmockkAll()

    private fun uploadSession(): GraphUploadSession {
        val gate = AccountThrottleGate(nowMillis = { 0L }, random = { 0.0 })
        return GraphUploadSession(GraphThrottle(gate))
    }

    /** POST (create session) → the upload URL; every PUT (chunk) → 202 Accepted. */
    private fun sessionClient() = FakeGraphHttpClient { request, _ ->
        if (request.method == "POST") {
            GraphResponse(status = 200, body = JSONObject().put("uploadUrl", "https://upload.example/1").toString())
        } else {
            GraphResponse(status = 202, body = "")
        }
    }

    @Test
    fun `requiresUploadSession is true only above the one-shot ceiling`() {
        val session = uploadSession()
        val fourMb = 4L * 1024 * 1024
        assertFalse(session.requiresUploadSession(fourMb), "exactly 4 MB still fits a one-shot upload")
        assertTrue(session.requiresUploadSession(fourMb + 1), "over 4 MB needs a chunked session")
    }

    @Test
    fun `an over-threshold attachment uploads in contiguous ranged chunks`() = runTest {
        val session = uploadSession()
        val client = sessionClient()
        val total = 4 * 1024 * 1024 + 500 // just over the 4 MB one-shot ceiling
        val content = ByteArray(total) { (it % 251).toByte() }
        assertTrue(session.requiresUploadSession(total.toLong()), "the payload is over-threshold")

        val createBody = JSONObject().put("AttachmentItem", JSONObject())
        val response = session.upload(accountId, client, createUrl, createBody, content, chunkSize = chunkMultiple)

        assertEquals(202, response.status)
        // First call creates the session; the rest are the chunk PUTs.
        val create = client.requests.first()
        assertEquals("POST", create.method)
        assertEquals(createUrl, create.url)
        val puts = client.requests.drop(1)
        val expectedChunks = (total + chunkMultiple - 1) / chunkMultiple
        assertEquals(expectedChunks, puts.size, "content is split into ceil(total / chunkSize) PUTs")
        assertTrue(puts.all { it.method == "PUT" && it.url == "https://upload.example/1" })

        // The ranges must be contiguous, start at 0, end at total-1, and reassemble to the original bytes.
        val reassembled = ByteArray(total)
        var expectedStart = 0
        puts.forEach { put ->
            val range = put.headers.getValue("Content-Range").removePrefix("bytes ").substringBefore('/')
            val start = range.substringBefore('-').toInt()
            val end = range.substringAfter('-').toInt()
            val chunkBody = put.body!!
            assertEquals(expectedStart, start, "chunk ranges must be contiguous")
            chunkBody.copyInto(reassembled, destinationOffset = start)
            assertEquals(end - start + 1, chunkBody.size, "Content-Range length must match the body")
            expectedStart = end + 1
        }
        assertEquals(total, expectedStart, "the ranges must cover the whole payload")
        assertTrue(content.contentEquals(reassembled), "the reassembled chunks must equal the original content")
    }

    @Test
    fun `a small payload still uploads as a single chunk`() = runTest {
        val client = sessionClient()

        uploadSession().upload(accountId, client, createUrl, JSONObject(), ByteArray(10) { 1 }, chunkMultiple)

        assertEquals(1, client.requests.count { it.method == "PUT" }, "content under one chunk is a single PUT")
    }

    @Test
    fun `a failed session-create returns without uploading`() = runTest {
        val client = FakeGraphHttpClient.always(GraphResponse(status = 500, body = "boom"))

        val response = uploadSession().upload(accountId, client, createUrl, JSONObject(), ByteArray(10) { 1 })

        assertEquals(500, response.status)
        assertEquals(1, client.callCount, "no chunk is uploaded when the session cannot be created")
    }

    @Test
    fun `empty content is rejected`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            uploadSession().upload(accountId, sessionClient(), createUrl, JSONObject(), ByteArray(0))
        }
    }

    @Test
    fun `a chunk size that is not a 320 KiB multiple is rejected`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            // 1000 is not a multiple of 320 KiB.
            uploadSession().upload(
                accountId,
                sessionClient(),
                createUrl,
                JSONObject(),
                ByteArray(10) { 1 },
                chunkSize = 1000,
            )
        }
    }
}
