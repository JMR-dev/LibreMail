// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail

import jakarta.mail.internet.InternetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.libremail.domain.model.OutgoingMessage
import org.libremail.mail.graph.GraphHttpClient
import org.libremail.mail.graph.GraphRequest
import org.libremail.mail.graph.GraphThrottle
import org.libremail.mail.graph.GraphTransportException
import org.libremail.mail.graph.isHttpSuccess
import org.libremail.reporting.AppLog
import org.libremail.reporting.accountLogRef
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thrown when a Graph `sendMail` attempt fails. [mayHaveSent] is true only when the request was
 * fully transmitted but the response could not be read — in that case the message may already be on
 * its way, so callers must NOT retry or fall back to another transport (doing so would duplicate it).
 */
class GraphSendException(message: String, val mayHaveSent: Boolean, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Sends mail via Microsoft Graph `me/sendMail` — Microsoft's preferred send path for Outlook /
 * Microsoft 365, used in place of SMTP. Authenticated with a Graph access token (Bearer).
 *
 * The request goes through [GraphThrottle] (issue #364), so a Graph 429/503 is honored — its
 * `Retry-After` is respected and the send retried after that wait — and recorded against the account's
 * shared reactive backoff gate (#360), which also cools that account's IMAP background work down. Only
 * after the honored retry is exhausted does a throttled or rejected response surface as a
 * [GraphSendException] (`mayHaveSent = false`, safe for the outbox to fall back to SMTP).
 */
@Singleton
class GraphSender @Inject constructor(private val httpClient: GraphHttpClient, private val throttle: GraphThrottle) {

    suspend fun send(
        accessToken: String,
        message: OutgoingMessage,
        attachments: List<SendableAttachment> = emptyList(),
    ) = withContext(Dispatchers.IO) {
        // Guard before any attachment is read into memory: Graph sendMail carries attachment bytes inline
        // (base64) in a single ~4 MB request, so an oversized file would blow that request limit and risk
        // an OOM from readBytes(). Fail with mayHaveSent=false so the outbox falls back to SMTP, which
        // streams attachments and handles far larger files (#298). (An over-4 MB attachment sent *through*
        // Graph would need a draft + createUploadSession chunked upload — see GraphUploadSession — which
        // requires the Mail.ReadWrite scope this send-only token does not hold; SMTP fallback is simpler.)
        attachments.firstOrNull { it.file.length() > MAX_ATTACHMENT_BYTES }?.let {
            AppLog.w(TAG, "Attachment over Graph sendMail size limit; not sending via Graph")
            throw GraphSendException("Attachment exceeds the Graph sendMail size limit", mayHaveSent = false)
        }
        val payload = buildSendMailPayload(message, attachments)
        val request = GraphRequest(
            method = "POST",
            url = SEND_MAIL_URL,
            headers = mapOf(
                "Authorization" to "Bearer $accessToken",
                "Content-Type" to "application/json; charset=utf-8",
            ),
            body = payload.toByteArray(Charsets.UTF_8),
        )
        val response = try {
            throttle.execute(message.accountId, httpClient, request, maxRetries = SEND_MAX_RETRIES)
        } catch (e: GraphTransportException) {
            // No HTTP response at all. A lost response means Graph may already have accepted+sent the
            // message, so callers must NOT fall back (it would duplicate); a transmit failure is safe.
            throw GraphSendException(
                if (e.mayHaveSent) {
                    "Graph sendMail sent but no response received"
                } else {
                    "Graph sendMail could not be transmitted"
                },
                mayHaveSent = e.mayHaveSent,
                cause = e,
            )
        }
        if (!isHttpSuccess(response.status)) {
            // An explicit non-2xx (including a 429 the retry budget couldn't clear) means Graph did not
            // send — safe for the outbox to fall back to SMTP.
            throw GraphSendException(
                "Graph sendMail failed (HTTP ${response.status}): ${response.body.take(ERROR_BODY_LIMIT)}",
                mayHaveSent = false,
            )
        }
        AppLog.i(TAG, "graph sendMail ok ${accountLogRef(message.accountId)}")
    }

    private companion object {
        const val TAG = "GraphSender"
        const val SEND_MAIL_URL = "https://graph.microsoft.com/v1.0/me/sendMail"

        // Per-file ceiling kept below Graph sendMail's ~4 MB whole-request cap, so one attachment can never
        // exceed the request limit or OOM when read into the base64 payload; larger files fall back to SMTP.
        const val MAX_ATTACHMENT_BYTES = 3L * 1024 * 1024

        // One honored Retry-After retry for a user-initiated send: respect the provider's explicit
        // "slow down" once, then fall back to SMTP rather than block the outbox drain for long.
        const val SEND_MAX_RETRIES = 1
        const val ERROR_BODY_LIMIT = 500
    }
}

/** Builds the Graph `sendMail` JSON body (a pure function, so it is unit-testable without a network). */
internal fun buildSendMailPayload(message: OutgoingMessage, attachments: List<SendableAttachment>): String {
    // Graph carries a single body object: send HTML when the message was formatted (Outlook renders
    // it and derives its own plaintext), otherwise plain text so unformatted mail is unchanged.
    val body = if (message.bodyHtml != null) {
        JSONObject().put("contentType", "HTML").put("content", message.bodyHtml)
    } else {
        JSONObject().put("contentType", "Text").put("content", message.body)
    }
    val mail = JSONObject()
        .put("subject", message.subject)
        .put("body", body)
        .put("toRecipients", recipientsJson(message.to))
    if (message.cc.isNotBlank()) {
        mail.put("ccRecipients", recipientsJson(message.cc))
    }
    if (message.bcc.isNotBlank()) {
        mail.put("bccRecipients", recipientsJson(message.bcc))
    }
    if (attachments.isNotEmpty()) {
        val items = JSONArray()
        attachments.forEach { attachment ->
            val obj = JSONObject()
                .put("@odata.type", "#microsoft.graph.fileAttachment")
                .put("name", attachment.file.name)
                .put("contentBytes", Base64.getEncoder().encodeToString(attachment.file.readBytes()))
            // An inline image the HTML references as `cid:contentId` — Graph renders it in the body
            // rather than listing it as a downloadable attachment. The content id is sanitized of
            // control characters before it enters the payload (issue #204, defense-in-depth).
            if (attachment.isInlineImage) {
                obj.put("isInline", true).put("contentId", sanitizeContentId(attachment.contentId))
            }
            items.put(obj)
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
