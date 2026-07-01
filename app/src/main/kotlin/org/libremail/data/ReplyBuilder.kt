// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data

import org.libremail.domain.model.ReplyMode
import org.libremail.mail.HtmlToText
import org.libremail.mail.ReplyContext
import org.libremail.richtext.RichTextContent
import org.libremail.richtext.RichTextHtml
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The pre-filled compose fields for a reply/forward. [body] is the plaintext form; [bodyHtml] is the
 * matching HTML (the quote rendered as a `<blockquote>`), so the reply can go out as
 * `multipart/alternative` without the user having to re-format the quote.
 */
data class ReplyContent(val to: String, val cc: String, val subject: String, val body: String, val bodyHtml: String)

/**
 * Pure builder that turns an original message ([ReplyContext]) into the pre-filled compose fields for a
 * reply, reply-all, or forward. Kept free of Android/IMAP dependencies so it can be unit-tested directly.
 *
 * HTML originals are quoted by first reducing them to readable text (via [HtmlToText]) and then
 * quoting that — never by prefixing "> " onto raw tags — so the quote can never corrupt the markup.
 * The plaintext quote's "> " / attribution structure is then rendered to a clean `<blockquote>` for
 * the HTML alternative.
 */
object ReplyBuilder {

    fun build(context: ReplyContext, mode: ReplyMode, selfEmail: String): ReplyContent = when (mode) {
        ReplyMode.REPLY -> reply(context, cc = "")
        ReplyMode.REPLY_ALL -> reply(context, cc = replyAllCc(context, selfEmail).joinToString(", "))
        ReplyMode.FORWARD -> {
            val body = forwardedBody(context)
            ReplyContent(
                to = "",
                cc = "",
                subject = prefixedSubject(context.subject, "Fwd:"),
                body = body,
                bodyHtml = htmlOf(body),
            )
        }
    }

    private fun reply(context: ReplyContext, cc: String): ReplyContent {
        val body = quotedReply(context)
        return ReplyContent(
            to = context.fromEmail,
            cc = cc,
            subject = prefixedSubject(context.subject, "Re:"),
            body = body,
            bodyHtml = htmlOf(body),
        )
    }

    /** Everyone on the original To/Cc except ourselves and the original sender (who becomes the To). */
    private fun replyAllCc(context: ReplyContext, selfEmail: String): List<String> = (
        context.toRecipients +
            context.ccRecipients
        )
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

    /** The original body as plain text (HTML stripped), suitable for quoting in a compose field. */
    private fun bodyText(context: ReplyContext): String =
        if (context.isHtml) HtmlToText.convert(context.body) else context.body

    /** Renders the plaintext quote (with its "> " markers) to the equivalent clean HTML. */
    private fun htmlOf(body: String): String = RichTextHtml.toHtml(RichTextContent(body))

    private fun formatDate(millis: Long): String =
        SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.US).format(Date(millis))
}
