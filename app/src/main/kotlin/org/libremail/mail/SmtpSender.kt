// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail

import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import java.io.File
import java.util.Date
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.libremail.domain.model.MailSecurity
import org.libremail.domain.model.OutgoingMessage
import org.libremail.domain.model.SmtpParams

/** Sends mail via SMTP over Jakarta/Angus Mail. Supports password and XOAUTH2 auth. */
@Singleton
class SmtpSender @Inject constructor() {

    suspend fun send(
        params: SmtpParams,
        from: String,
        message: OutgoingMessage,
        attachments: List<File> = emptyList(),
    ) =
        withContext(Dispatchers.IO) {
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
                subject = message.subject
                sentDate = Date()
            }
            if (attachments.isEmpty()) {
                mime.setText(message.body, "UTF-8")
            } else {
                val multipart = MimeMultipart()
                multipart.addBodyPart(MimeBodyPart().apply { setText(message.body, "UTF-8") })
                attachments.forEach { file ->
                    multipart.addBodyPart(MimeBodyPart().apply { attachFile(file) })
                }
                mime.setContent(multipart)
            }

            val transport = session.getTransport(protocol)
            transport.connect(params.host, params.port, params.username, params.secret)
            try {
                transport.sendMessage(mime, mime.allRecipients)
            } finally {
                runCatching { transport.close() }
            }
        }

    private companion object {
        const val TIMEOUT_MS = "15000"
    }
}
