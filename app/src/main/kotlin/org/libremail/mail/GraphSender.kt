// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail

import java.io.File
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
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val body = (connection.errorStream ?: connection.inputStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                error("Graph sendMail failed (HTTP $code): ${body.take(500)}")
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

/** Splits a comma/semicolon-separated address list into Graph `emailAddress` recipient objects. */
private fun recipientsJson(addresses: String): JSONArray {
    val array = JSONArray()
    addresses.split(",", ";")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .forEach { address ->
            array.put(JSONObject().put("emailAddress", JSONObject().put("address", address)))
        }
    return array
}
