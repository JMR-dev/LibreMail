// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail

import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.internet.MimePart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.libremail.domain.model.MailSecurity
import org.libremail.domain.model.OutgoingMessage
import org.libremail.domain.model.SmtpParams
import java.util.Date
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton

/** Sends mail via SMTP over Jakarta/Angus Mail. Supports password and XOAUTH2 auth. */
@Singleton
class SmtpSender @Inject constructor() {

    suspend fun send(
        params: SmtpParams,
        from: String,
        message: OutgoingMessage,
        attachments: List<SendableAttachment> = emptyList(),
    ) = withContext(Dispatchers.IO) {
        val protocol = if (params.security == MailSecurity.SSL_TLS) "smtps" else "smtp"
        val props = Properties().apply {
            put("mail.transport.protocol", protocol)
            put("mail.$protocol.host", params.host)
            put("mail.$protocol.port", params.port.toString())
            put("mail.$protocol.auth", "true")
            put("mail.$protocol.connectiontimeout", TIMEOUT_MS)
            put("mail.$protocol.timeout", TIMEOUT_MS)
            put("mail.$protocol.writetimeout", TIMEOUT_MS)
            if (params.security == MailSecurity.SSL_TLS) {
                put("mail.$protocol.ssl.enable", "true")
            }
            if (params.security == MailSecurity.STARTTLS) {
                put("mail.$protocol.starttls.enable", "true")
                put("mail.$protocol.starttls.required", params.strictStartTls.toString())
            }
            // Verify the server certificate matches the host whenever TLS is used (explicit so a
            // future Angus default change can't silently disable it). No-op for MailSecurity.NONE.
            put("mail.$protocol.ssl.checkserveridentity", "true")
            if (params.useXoauth2) {
                put("mail.$protocol.auth.mechanisms", "XOAUTH2")
            }
        }

        val session = Session.getInstance(props)
        val mime = MimeMessage(session).apply {
            setFrom(InternetAddress(from))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(message.to))
            if (message.cc.isNotBlank()) {
                setRecipients(Message.RecipientType.CC, InternetAddress.parse(message.cc))
            }
            if (message.bcc.isNotBlank()) {
                setRecipients(Message.RecipientType.BCC, InternetAddress.parse(message.bcc))
            }
            subject = message.subject
            sentDate = Date()
        }
        applyBody(mime, message, attachments)

        val transport = session.getTransport(protocol)
        transport.connect(params.host, params.port, params.username, params.secret)
        try {
            transport.sendMessage(mime, mime.allRecipients)
        } finally {
            runCatching { transport.close() }
        }
    }

    /**
     * Sets the message body and attaches files:
     *  - plaintext-only ([OutgoingMessage.bodyHtml] null): a single `text/plain` part, exactly as
     *    before, so unformatted mail is unchanged on the wire;
     *  - formatted: a `multipart/alternative` of `text/plain` (fallback, first) + `text/html`
     *    (preferred, last, per RFC 2046).
     *
     * Inline images (referenced by the HTML as `cid:…`) wrap the body in a `multipart/related` so a
     * client can resolve those references; each carries a `Content-ID` and inline disposition. Regular
     * attachments keep today's shape — the body (related or plain) becomes the first part of a
     * `multipart/mixed`, followed by the attachment parts.
     */
    private fun applyBody(mime: MimeMessage, message: OutgoingMessage, attachments: List<SendableAttachment>) {
        val inline = attachments.filter { it.isInlineImage }
        val regular = attachments.filterNot { it.isInlineImage }
        when {
            inline.isEmpty() && regular.isEmpty() -> setBody(mime, message)
            regular.isEmpty() -> mime.setContent(relatedBody(message, inline))
            else -> {
                val mixed = MimeMultipart("mixed")
                mixed.addBodyPart(bodyPart(message, inline))
                regular.forEach { mixed.addBodyPart(MimeBodyPart().apply { attachFile(it.file) }) }
                mime.setContent(mixed)
            }
        }
    }

    /** The body part for a `multipart/mixed`: the plain/alternative body, wrapped in related if inline. */
    private fun bodyPart(message: OutgoingMessage, inline: List<SendableAttachment>): MimeBodyPart =
        if (inline.isEmpty()) {
            MimeBodyPart().also { setBody(it, message) }
        } else {
            MimeBodyPart().apply { setContent(relatedBody(message, inline)) }
        }

    /** A `multipart/related`: the body (plain/alternative) followed by each inline image part. */
    private fun relatedBody(message: OutgoingMessage, inline: List<SendableAttachment>): MimeMultipart =
        MimeMultipart("related").apply {
            addBodyPart(MimeBodyPart().also { setBody(it, message) })
            inline.forEach { addBodyPart(inlinePart(it)) }
        }

    /** An inline image part: attached bytes, a `Content-ID` the HTML's `cid:` matches, inline disposition. */
    private fun inlinePart(attachment: SendableAttachment): MimeBodyPart = MimeBodyPart().apply {
        attachFile(attachment.file)
        contentID = "<${attachment.contentId}>"
        setDisposition(MimeBodyPart.INLINE)
    }

    /** Writes the body onto [part]: plain text, or a text/plain + text/html alternative when formatted. */
    private fun setBody(part: MimePart, message: OutgoingMessage) {
        val html = message.bodyHtml
        if (html == null) {
            part.setText(message.body, "UTF-8")
        } else {
            part.setContent(alternative(message.body, html))
        }
    }

    private fun alternative(plain: String, html: String): MimeMultipart = MimeMultipart("alternative").apply {
        addBodyPart(MimeBodyPart().apply { setText(plain, "UTF-8") })
        addBodyPart(MimeBodyPart().apply { setContent(html, "text/html; charset=UTF-8") })
    }

    private companion object {
        const val TIMEOUT_MS = "15000"
    }
}
