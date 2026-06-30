// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail

import jakarta.mail.internet.InternetAddress
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.libremail.domain.model.OutgoingMessage

/**
 * Thrown when a Graph `sendMail` attempt fails. [mayHaveSent] is true only when the request was
 * fully transmitted but the response could not be read — in that case the message may already be on
 * its way, so callers must NOT retry or fall back to another transport (doing so would duplicate it).
 */
class GraphSendException(
    message: String,
    val mayHaveSent: Boolean,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Sends mail via Microsoft Graph `me/sendMail` — Microsoft's preferred send path for Outlook /
 * Microsoft 365, used in place of SMTP. Authenticated with a Graph access token (Bearer).
 */
@Singleton
class GraphSender @Inject constructor() {

    suspend fun send(
        accessToken: String,
        message: OutgoingMessage,
        attachments: List<File> = emptyList(),
    ) = withContext(Dispatchers.IO) {
        val payload = buildSendMailPayload(message, attachments)
        val connection = (URL(SEND_MAIL_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            // Failure here means the request never reached Graph — safe to fall back/retry.
            try {
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            } catch (e: IOException) {
                throw GraphSendException("Graph sendMail could not be transmitted", mayHaveSent = false, cause = e)
            }
            // The request was fully sent; if we can't read the response, Graph may already have
            // accepted and sent it — do not fall back to SMTP or the message would be duplicated.
            val code = try {
                connection.responseCode
            } catch (e: IOException) {
                throw GraphSendException("Graph sendMail sent but no response received", mayHaveSent = true, cause = e)
            }
            if (code !in 200..299) {
                val body = (connection.errorStream ?: connection.inputStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                // An explicit non-2xx means Graph rejected (did not send) — safe to fall back.
                throw GraphSendException("Graph sendMail failed (HTTP $code): ${body.take(500)}", mayHaveSent = false)
            }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val SEND_MAIL_URL = "https://graph.microsoft.com/v1.0/me/sendMail"
        const val TIMEOUT_MS = 15_000
    }
}

/** Builds the Graph `sendMail` JSON body (a pure function, so it is unit-testable without a network). */
internal fun buildSendMailPayload(message: OutgoingMessage, attachments: List<File>): String {
    val mail = JSONObject()
        .put("subject", message.subject)
        .put("body", JSONObject().put("contentType", "Text").put("content", message.body))
        .put("toRecipients", recipientsJson(message.to))
    if (message.cc.isNotBlank()) {
        mail.put("ccRecipients", recipientsJson(message.cc))
    }
    if (attachments.isNotEmpty()) {
        val items = JSONArray()
        attachments.forEach { file ->
            items.put(
                JSONObject()
                    .put("@odata.type", "#microsoft.graph.fileAttachment")
                    .put("name", file.name)
                    .put("contentBytes", Base64.getEncoder().encodeToString(file.readBytes())),
            )
        }
        mail.put("attachments", items)
    }
    return JSONObject().put("message", mail).put("saveToSentItems", true).toString()
}

/**
 * Parses an address list into Graph `emailAddress` recipient objects. Uses RFC 822 parsing (the
 * same as the SMTP path) so display-name recipients like `John Doe <john@example.com>` — and commas
 * inside quoted display names — produce a valid bare `address` (plus an optional `name`).
 */
private fun recipientsJson(addresses: String): JSONArray {
    val array = JSONArray()
    val parsed = runCatching { InternetAddress.parse(addresses, false) }.getOrNull() ?: emptyArray()
    parsed.forEach { addr ->
        val email = addr.address?.trim().orEmpty()
        if (email.isEmpty()) return@forEach
        val emailAddress = JSONObject().put("address", email)
        addr.personal?.takeIf { it.isNotBlank() }?.let { emailAddress.put("name", it) }
        array.put(JSONObject().put("emailAddress", emailAddress))
    }
    return array
}
