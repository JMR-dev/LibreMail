// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail

import org.libremail.domain.model.Account
import org.libremail.domain.model.MailProvider
import org.libremail.domain.model.OutgoingMessage
import org.libremail.reporting.AppLog
import org.libremail.reporting.accountLogRef

private const val BYTES_PER_MB = 1024L * 1024L

/** Rounds [this] many bytes up to the nearest whole megabyte, for a human-readable size in an error message. */
private fun Long.toWholeMb(): Long = (this + BYTES_PER_MB - 1) / BYTES_PER_MB

/**
 * Thrown when an outgoing message would exceed iCloud Mail's documented size cap (issue #363). The
 * message text carries only byte counts — never a subject, address, or body fragment — so it is safe to
 * surface verbatim as the outbox row's user-visible error ([org.libremail.data.sync.SendWorker]) and to
 * pass to [AppLog], per the repo's PII-free logging rule.
 */
class MessageTooLargeException(val estimatedBytes: Long, val limitBytes: Long) :
    Exception(
        "Message is too large for iCloud Mail (about ${estimatedBytes.toWholeMb()} MB; " +
            "the limit is ${limitBytes.toWholeMb()} MB)",
    )

/**
 * Enforces iCloud Mail's documented ~20 MB outgoing message-size cap (issue #363) before [SmtpSender] is
 * ever asked to send. Apple's limit is the message as it travels the wire — body plus MIME/base64-encoded
 * attachments — so [requireWithinLimit] estimates that encoded size (base64 inflates binary bytes by
 * roughly 4/3) rather than comparing raw attachment file sizes directly against 20 MB, which would
 * under-count and let a doomed send through to a real server-side rejection. Mirrors [GraphSender]'s
 * pre-send `MAX_ATTACHMENT_BYTES` guard for the same reason: fail fast and locally, with a clear message,
 * rather than spend a connection on a send that cannot succeed.
 *
 * A no-op for every other provider — Gmail/Yahoo/AOL/Outlook each document their own outgoing-size
 * limits, tracked by issues #361/#362/#364, kept as their own provider-scoped policy rather than a shared
 * table so the four tickets land independently.
 */
object IcloudSendLimits {

    /** Apple's documented outgoing message-size cap (body + attachments), unless using Mail Drop. */
    const val DOCUMENTED_LIMIT_BYTES = 20L * BYTES_PER_MB

    /**
     * Throws [MessageTooLargeException] when [account] is an iCloud account and [message] plus
     * [attachments]' estimated encoded size exceeds [DOCUMENTED_LIMIT_BYTES]. A no-op for every other
     * provider, and for an iCloud message that fits.
     */
    fun requireWithinLimit(account: Account, message: OutgoingMessage, attachments: List<SendableAttachment>) {
        if (MailProvider.forImapHost(account.imap.host) != MailProvider.ICLOUD) return
        val estimated = estimatedEncodedBytes(message, attachments)
        if (estimated <= DOCUMENTED_LIMIT_BYTES) return
        AppLog.w(
            TAG,
            "${accountLogRef(account.id)} outgoing message over iCloud size cap: " +
                "estimated=${estimated}B limit=${DOCUMENTED_LIMIT_BYTES}B",
        )
        throw MessageTooLargeException(estimated, DOCUMENTED_LIMIT_BYTES)
    }

    /**
     * Estimates the message's size once it is on the wire: plain/HTML body bytes as-is (this app's MIME
     * structure never base64-encodes the text parts — see [SmtpSender.setBody]) plus each attachment's
     * *base64-encoded* size (3 raw bytes become 4 encoded chars, rounded up to the next whole group the
     * way a real encoder pads a partial one).
     */
    private fun estimatedEncodedBytes(message: OutgoingMessage, attachments: List<SendableAttachment>): Long {
        val textBytes = message.body.toByteArray(Charsets.UTF_8).size.toLong() +
            (message.bodyHtml?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L)
        val attachmentEncodedBytes = attachments.sumOf { it.file.length().toBase64EncodedSize() }
        return textBytes + attachmentEncodedBytes
    }

    private fun Long.toBase64EncodedSize(): Long =
        ((this + BASE64_RAW_GROUP_SIZE - 1) / BASE64_RAW_GROUP_SIZE) * BASE64_ENCODED_GROUP_SIZE

    private const val TAG = "IcloudSendLimits"

    /** Base64 groups 3 raw bytes... */
    private const val BASE64_RAW_GROUP_SIZE = 3L

    /** ...into 4 encoded characters. */
    private const val BASE64_ENCODED_GROUP_SIZE = 4L
}
