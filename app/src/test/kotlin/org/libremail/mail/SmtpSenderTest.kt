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
