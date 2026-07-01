// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.GreenMailUtil
import com.icegreen.greenmail.util.ServerSetupTest
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.domain.model.MailSecurity
import org.libremail.domain.model.OutgoingMessage
import org.libremail.domain.model.SmtpParams
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmtpSenderTest {

    private lateinit var greenMail: GreenMail
    private val sender = SmtpSender()

    @Before
    fun setUp() {
        greenMail = GreenMail(ServerSetupTest.SMTP)
        greenMail.start()
        greenMail.setUser("sender@example.org", "secret")
    }

    @After
    fun tearDown() {
        greenMail.stop()
    }

    @Test
    fun `send delivers the message to the SMTP server`() = runTest {
        val params = SmtpParams(
            host = "127.0.0.1",
            port = greenMail.smtp.port,
            security = MailSecurity.NONE,
            username = "sender@example.org",
            secret = "secret",
            useXoauth2 = false,
        )

        sender.send(
            params = params,
            from = "sender@example.org",
            message = OutgoingMessage(
                accountId = "x",
                to = "bob@example.org",
                subject = "Hi Bob",
                body = "Hello there from LibreMail.",
            ),
        )

        greenMail.waitForIncomingEmail(1)
        val received = greenMail.receivedMessages
        assertEquals(1, received.size)
        assertEquals("Hi Bob", received[0].subject)
        assertTrue(GreenMailUtil.getBody(received[0]).contains("Hello there"))
    }

    @Test
    fun `a formatted message is sent as multipart alternative with both parts`() = runTest {
        val params = SmtpParams(
            host = "127.0.0.1",
            port = greenMail.smtp.port,
            security = MailSecurity.NONE,
            username = "sender@example.org",
            secret = "secret",
            useXoauth2 = false,
        )

        sender.send(
            params = params,
            from = "sender@example.org",
            message = OutgoingMessage(
                accountId = "x",
                to = "bob@example.org",
                subject = "Rich",
                body = "Hello world",
                bodyHtml = "<p>Hello <b>world</b></p>",
            ),
        )

        greenMail.waitForIncomingEmail(1)
        val received = greenMail.receivedMessages.single()
        assertTrue(received.contentType.contains("multipart/alternative", ignoreCase = true), received.contentType)
        val raw = GreenMailUtil.getWholeMessage(received)
        assertTrue(raw.contains("text/plain", ignoreCase = true), "missing text/plain")
        assertTrue(raw.contains("text/html", ignoreCase = true), "missing text/html")
        assertTrue(raw.contains("<b>world</b>"), "missing html body")
        assertTrue(raw.contains("Hello world"), "missing plaintext fallback")
    }

    @Test
    fun `a formatted message with an attachment nests the alternative inside mixed`() = runTest {
        val file = File.createTempFile("libremail-note", ".txt").apply { writeText("attached note") }
        val params = SmtpParams(
            host = "127.0.0.1",
            port = greenMail.smtp.port,
            security = MailSecurity.NONE,
            username = "sender@example.org",
            secret = "secret",
            useXoauth2 = false,
        )

        sender.send(
            params = params,
            from = "sender@example.org",
            message = OutgoingMessage(
                accountId = "x",
                to = "bob@example.org",
                subject = "Rich + file",
                body = "Body",
                bodyHtml = "<p><b>Body</b></p>",
            ),
            attachments = listOf(file),
        )

        greenMail.waitForIncomingEmail(1)
        val received = greenMail.receivedMessages.single()
        assertTrue(received.contentType.contains("multipart/mixed", ignoreCase = true), received.contentType)
        val raw = GreenMailUtil.getWholeMessage(received)
        assertTrue(raw.contains("multipart/alternative", ignoreCase = true), "missing alternative part")
        assertTrue(raw.contains(file.name), "missing attachment")
        assertTrue(raw.contains("<b>Body</b>"), "missing html body")
        file.delete()
    }

    @Test
    fun `send delivers a message with an attachment`() = runTest {
        val file = File.createTempFile("libremail-report", ".txt").apply { writeText("quarterly numbers") }
        val params = SmtpParams(
            host = "127.0.0.1",
            port = greenMail.smtp.port,
            security = MailSecurity.NONE,
            username = "sender@example.org",
            secret = "secret",
            useXoauth2 = false,
        )

        sender.send(
            params = params,
            from = "sender@example.org",
            message = OutgoingMessage(
                accountId = "x",
                to = "bob@example.org",
                subject = "With file",
                body = "See the attached report.",
            ),
            attachments = listOf(file),
        )

        greenMail.waitForIncomingEmail(1)
        val received = greenMail.receivedMessages.single()
        assertTrue(received.contentType.contains("multipart", ignoreCase = true))
        val raw = GreenMailUtil.getWholeMessage(received)
        assertTrue(raw.contains("See the attached report."))
        assertTrue(raw.contains(file.name))
        file.delete()
    }
}
