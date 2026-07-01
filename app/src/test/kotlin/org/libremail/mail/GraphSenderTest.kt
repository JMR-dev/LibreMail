// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail

import org.json.JSONObject
import org.junit.Test
import org.libremail.domain.model.OutgoingMessage
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphSenderTest {

    private fun message(to: String, cc: String = "", subject: String = "Hi", body: String = "Body") =
        OutgoingMessage(accountId = "outlook:me@example.com", to = to, cc = cc, subject = subject, body = body)

    @Test
    fun `payload carries subject, body and parsed recipients`() {
        val json = JSONObject(buildSendMailPayload(message(to = "a@x.com, b@y.com", cc = "c@z.com"), emptyList()))
        assertTrue(json.getBoolean("saveToSentItems"))

        val msg = json.getJSONObject("message")
        assertEquals("Hi", msg.getString("subject"))
        assertEquals("Text", msg.getJSONObject("body").getString("contentType"))
        assertEquals("Body", msg.getJSONObject("body").getString("content"))

        val to = msg.getJSONArray("toRecipients")
        assertEquals(2, to.length())
        assertEquals("a@x.com", to.getJSONObject(0).getJSONObject("emailAddress").getString("address"))
        assertEquals("b@y.com", to.getJSONObject(1).getJSONObject("emailAddress").getString("address"))

        val cc = msg.getJSONArray("ccRecipients")
        assertEquals(1, cc.length())
        assertEquals("c@z.com", cc.getJSONObject(0).getJSONObject("emailAddress").getString("address"))
    }

    @Test
    fun `recipients parse RFC822 display names into bare addresses`() {
        val json = JSONObject(
            buildSendMailPayload(
                message(to = "John Doe <john@example.com>", cc = "\"Doe, Jane\" <jane@example.com>"),
                emptyList(),
            ),
        )
        val msg = json.getJSONObject("message")

        val to = msg.getJSONArray("toRecipients")
        assertEquals(1, to.length())
        val toAddress = to.getJSONObject(0).getJSONObject("emailAddress")
        assertEquals("john@example.com", toAddress.getString("address"))
        assertEquals("John Doe", toAddress.getString("name"))

        // The comma inside the quoted display name must not be treated as an address separator.
        val cc = msg.getJSONArray("ccRecipients")
        assertEquals(1, cc.length())
        assertEquals("jane@example.com", cc.getJSONObject(0).getJSONObject("emailAddress").getString("address"))
    }

    @Test
    fun `payload carries bcc recipients when present`() {
        val json = JSONObject(
            buildSendMailPayload(message(to = "a@x.com").copy(bcc = "hidden@z.com, more@z.com"), emptyList()),
        )
        val bcc = json.getJSONObject("message").getJSONArray("bccRecipients")
        assertEquals(2, bcc.length())
        assertEquals("hidden@z.com", bcc.getJSONObject(0).getJSONObject("emailAddress").getString("address"))
        assertEquals("more@z.com", bcc.getJSONObject(1).getJSONObject("emailAddress").getString("address"))
    }

    @Test
    fun `payload omits cc and bcc when blank and encodes attachments as base64`() {
        val file = File.createTempFile("graph-att", ".txt").apply { writeText("hello") }
        try {
            val msg = JSONObject(buildSendMailPayload(message(to = "a@x.com"), listOf(file))).getJSONObject("message")
            assertFalse(msg.has("ccRecipients"))
            assertFalse(msg.has("bccRecipients"))

            val attachments = msg.getJSONArray("attachments")
            assertEquals(1, attachments.length())
            val attachment = attachments.getJSONObject(0)
            assertEquals("#microsoft.graph.fileAttachment", attachment.getString("@odata.type"))
            assertEquals(file.name, attachment.getString("name"))
            assertEquals("aGVsbG8=", attachment.getString("contentBytes")) // base64("hello")
        } finally {
            file.delete()
        }
    }
}
