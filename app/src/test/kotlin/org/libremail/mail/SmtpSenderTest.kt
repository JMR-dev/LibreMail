// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.GreenMailUtil
import com.icegreen.greenmail.util.ServerSetupTest
import jakarta.mail.Multipart
import jakarta.mail.Part
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.domain.model.MailSecurity
import org.libremail.domain.model.OutgoingMessage
import org.libremail.domain.model.SmtpParams
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
            attachments = listOf(SendableAttachment(file)),
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
    fun `an inline image is sent as multipart related with a matching Content-ID`() = runTest {
        val image = File.createTempFile("libremail-inline", ".png").apply { writeText("PNGDATA") }
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
                subject = "Inline",
                body = "See image",
                bodyHtml = "<p>See <img src=\"cid:logo@libremail\" alt=\"logo\"></p>",
            ),
            attachments = listOf(SendableAttachment(image, contentId = "logo@libremail", isInline = true)),
        )

        greenMail.waitForIncomingEmail(1)
        val received = greenMail.receivedMessages.single()
        assertTrue(received.contentType.contains("multipart/related", ignoreCase = true), received.contentType)
        val raw = GreenMailUtil.getWholeMessage(received)
        // The HTML's cid reference and the image part's Content-ID must name the same content id.
        assertTrue(raw.contains("cid:logo@libremail"), "html must reference the cid")
        assertTrue(raw.contains("<logo@libremail>"), "inline part must carry a matching Content-ID")
        assertTrue(raw.contains("multipart/alternative", ignoreCase = true), "the body stays an alternative")
        image.delete()
    }

    @Test
    fun `a Content-ID carrying CRLF cannot inject a MIME header line`() = runTest {
        val image = File.createTempFile("libremail-inline", ".png").apply { writeText("PNGDATA") }
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
                subject = "Inline injection",
                body = "See image",
                bodyHtml = "<p>See <img src=\"cid:logo@libremail\"></p>",
            ),
            // A crafted content id that, unsanitized, would break out of the Content-ID header and add
            // its own `X-Injected` header line to the inline part.
            attachments = listOf(
                SendableAttachment(image, contentId = "logo@libremail\r\nX-Injected: evil", isInline = true),
            ),
        )

        greenMail.waitForIncomingEmail(1)
        val received = greenMail.receivedMessages.single()
        // The CR/LF is stripped, so the crafted text never becomes its own header on any MIME part...
        assertFalse(hasHeaderAnywhere(received, "X-Injected"), "Content-ID CR/LF must not inject a header")
        // ...and the Content-ID that is emitted stays on a single line.
        val cid = contentIdAnywhere(received)
        assertTrue(cid != null && '\r' !in cid && '\n' !in cid, "Content-ID must be a single line: $cid")
        image.delete()
    }

    /** True if [part] or any nested MIME part carries a header named [name]. */
    private fun hasHeaderAnywhere(part: Part, name: String): Boolean {
        if (!part.getHeader(name).isNullOrEmpty()) return true
        val content = runCatching { part.content }.getOrNull()
        return content is Multipart && (0 until content.count).any { hasHeaderAnywhere(content.getBodyPart(it), name) }
    }

    /** The first `Content-ID` header found on [part] or any nested MIME part, or null. */
    private fun contentIdAnywhere(part: Part): String? {
        part.getHeader("Content-ID")?.firstOrNull()?.let { return it }
        val content = runCatching { part.content }.getOrNull()
        if (content is Multipart) {
            for (i in 0 until content.count) contentIdAnywhere(content.getBodyPart(i))?.let { return it }
        }
        return null
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
            attachments = listOf(SendableAttachment(file)),
        )

        greenMail.waitForIncomingEmail(1)
        val received = greenMail.receivedMessages.single()
        assertTrue(received.contentType.contains("multipart", ignoreCase = true))
        val raw = GreenMailUtil.getWholeMessage(received)
        assertTrue(raw.contains("See the attached report."))
        assertTrue(raw.contains(file.name))
        file.delete()
    }

    @Test
    fun `send delivers to bcc recipients without leaking them in the headers`() = runTest {
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
                bcc = "eve@example.org",
                subject = "FYI",
                body = "Blind copy test.",
            ),
        )

        // Both the To and Bcc recipients are in the SMTP envelope, so GreenMail delivers two copies.
        greenMail.waitForIncomingEmail(2)
        val received = greenMail.receivedMessages
        assertEquals(2, received.size)
        // Jakarta Mail strips the Bcc header before transmission, so it must not appear on the wire.
        received.forEach { assertNull(it.getHeader("Bcc")) }
    }

    @Test
    fun `send copies cc recipients on the wire`() = runTest {
        sender.send(
            params = params(),
            from = "sender@example.org",
            message = OutgoingMessage(
                accountId = "x",
                to = "bob@example.org",
                cc = "carol@example.org",
                subject = "With CC",
                body = "Hello cc.",
            ),
        )

        // To + Cc are both in the envelope, so GreenMail delivers two copies; Cc stays on the wire.
        greenMail.waitForIncomingEmail(2)
        val received = greenMail.receivedMessages
        assertEquals(2, received.size)
        assertTrue(GreenMailUtil.getWholeMessage(received[0]).contains("carol@example.org"), "missing Cc header")
    }

    @Test
    fun `an inline image plus a regular attachment nests a related body inside mixed`() = runTest {
        val image = File.createTempFile("libremail-inline", ".png").apply { writeText("PNGDATA") }
        val doc = File.createTempFile("libremail-doc", ".txt").apply { writeText("attached doc") }

        sender.send(
            params = params(),
            from = "sender@example.org",
            message = OutgoingMessage(
                accountId = "x",
                to = "bob@example.org",
                subject = "Inline + file",
                body = "See image and file",
                bodyHtml = "<p>See <img src=\"cid:logo@libremail\"></p>",
            ),
            attachments = listOf(
                SendableAttachment(image, contentId = "logo@libremail", isInline = true),
                SendableAttachment(doc),
            ),
        )

        greenMail.waitForIncomingEmail(1)
        val received = greenMail.receivedMessages.single()
        // A regular attachment makes the top level multipart/mixed; the inline image wraps the body in
        // a multipart/related nested as the first mixed part (the `bodyPart` inline branch).
        assertTrue(received.contentType.contains("multipart/mixed", ignoreCase = true), received.contentType)
        val raw = GreenMailUtil.getWholeMessage(received)
        assertTrue(raw.contains("multipart/related", ignoreCase = true), "inline body must be wrapped in related")
        assertTrue(raw.contains("<logo@libremail>"), "inline part must carry a matching Content-ID")
        assertTrue(raw.contains(doc.name), "regular attachment must be present")
        image.delete()
        doc.delete()
    }

    @Test
    fun `send fails and delivers nothing when a required STARTTLS upgrade is unavailable`() = runTest {
        // The plain GreenMail server does not advertise STARTTLS; with a strict (required) STARTTLS
        // policy the client refuses to fall back to plaintext, so send throws and nothing is delivered.
        // Exercises the STARTTLS + XOAUTH2 branches of the property builder and the connect error path.
        assertFailsWith<Exception> {
            sender.send(
                params = params(security = MailSecurity.STARTTLS, useXoauth2 = true),
                from = "sender@example.org",
                message = OutgoingMessage(accountId = "x", to = "bob@example.org", subject = "No TLS", body = "x"),
            )
        }
        assertTrue(greenMail.receivedMessages.isEmpty())
    }

    @Test
    fun `send fails when implicit TLS cannot be negotiated`() = runTest {
        // Driving the implicit-TLS (SSL_TLS) path at the plain SMTP port makes the handshake fail, so
        // send throws — covering the ssl.enable branch of the property builder and the connect error path.
        assertFailsWith<Exception> {
            sender.send(
                params = params(security = MailSecurity.SSL_TLS),
                from = "sender@example.org",
                message = OutgoingMessage(accountId = "x", to = "bob@example.org", subject = "No TLS", body = "x"),
            )
        }
    }

    private fun params(security: MailSecurity = MailSecurity.NONE, useXoauth2: Boolean = false) = SmtpParams(
        host = "127.0.0.1",
        port = greenMail.smtp.port,
        security = security,
        username = "sender@example.org",
        secret = "secret",
        useXoauth2 = useXoauth2,
    )
}
