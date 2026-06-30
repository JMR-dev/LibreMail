// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.libremail.domain.model.ReplyMode
import org.libremail.mail.ReplyContext

/** The pre-filled compose fields for a reply/forward (everything else the user fills in). */
data class ReplyContent(
    val to: String,
    val cc: String,
    val subject: String,
    val body: String,
)

/**
 * Pure builder that turns an original message ([ReplyContext]) into the pre-filled compose fields for a
 * reply, reply-all, or forward. Kept free of Android/IMAP dependencies so it can be unit-tested directly.
 */
object ReplyBuilder {

    fun build(context: ReplyContext, mode: ReplyMode, selfEmail: String): ReplyContent = when (mode) {
        ReplyMode.REPLY -> ReplyContent(
            to = context.fromEmail,
            cc = "",
            subject = prefixedSubject(context.subject, "Re:"),
            body = quotedReply(context),
        )

        ReplyMode.REPLY_ALL -> ReplyContent(
            to = context.fromEmail,
            cc = replyAllCc(context, selfEmail).joinToString(", "),
            subject = prefixedSubject(context.subject, "Re:"),
            body = quotedReply(context),
        )

        ReplyMode.FORWARD -> ReplyContent(
            to = "",
            cc = "",
            subject = prefixedSubject(context.subject, "Fwd:"),
            body = forwardedBody(context),
        )
    }

    /** Everyone on the original To/Cc except ourselves and the original sender (who becomes the To). */
    private fun replyAllCc(context: ReplyContext, selfEmail: String): List<String> =
        (context.toRecipients + context.ccRecipients)
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .filterNot { it.equals(selfEmail, ignoreCase = true) || it.equals(context.fromEmail, ignoreCase = true) }

    /** Adds [prefix] unless the subject already starts with it (case-insensitive), avoiding "Re: Re:". */
    private fun prefixedSubject(subject: String, prefix: String): String {
        val trimmed = subject.trim()
        return if (trimmed.startsWith(prefix, ignoreCase = true)) trimmed else "$prefix $trimmed"
    }

    private fun quotedReply(context: ReplyContext): String {
        val original = bodyText(context).lineSequence().joinToString("\n") { "> $it" }
        return "\n\nOn ${formatDate(context.sentDateMillis)}, ${context.fromEmail} wrote:\n$original"
    }

    private fun forwardedBody(context: ReplyContext): String = buildString {
        append("\n\n---------- Forwarded message ----------\n")
        append("From: ${context.fromEmail}\n")
        append("Date: ${formatDate(context.sentDateMillis)}\n")
        append("Subject: ${context.subject}\n")
        append("To: ${context.toRecipients.joinToString(", ")}\n\n")
        append(bodyText(context))
    }

    /** The original body as plain text (HTML stripped), suitable for quoting in a plain-text compose. */
    private fun bodyText(context: ReplyContext): String =
        if (context.isHtml) htmlToText(context.body) else context.body

    private fun htmlToText(html: String): String =
        html
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</p\\s*>"), "\n\n")
            .replace(Regex("<[^>]*>"), "")
            .replace(Regex("[ \\t]+"), " ")
            .trim()

    private fun formatDate(millis: Long): String =
        SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.US).format(Date(millis))
}
