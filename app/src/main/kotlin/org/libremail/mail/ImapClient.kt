// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail

import android.util.Log
import jakarta.mail.FetchProfile
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.UIDFolder
import jakarta.mail.event.MessageCountAdapter
import jakarta.mail.event.MessageCountEvent
import jakarta.mail.internet.ContentType
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeUtility
import jakarta.mail.search.BodyTerm
import jakarta.mail.search.FromStringTerm
import jakarta.mail.search.OrTerm
import jakarta.mail.search.SubjectTerm
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.angus.mail.imap.IMAPFolder
import org.libremail.domain.model.ImapConnectionParams
import org.libremail.domain.model.MailSecurity

/** A message header fetched from the server (no body — that arrives with the reader). */
data class FetchedMessage(
    val uid: String,
    val sender: String,
    val senderEmail: String,
    val subject: String,
    val timestampMillis: Long,
    val isRead: Boolean,
    val isFlagged: Boolean,
)

/** A message body extracted from the server, with metadata for any attachment parts. */
data class MessageContent(
    val body: String,
    val isHtml: Boolean,
    val attachments: List<AttachmentPart> = emptyList(),
)

/** Metadata for one attachment part. [partIndex] is its position in attachment-tree order. */
data class AttachmentPart(
    val partIndex: Int,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
)

/** A downloaded attachment's bytes plus the metadata needed to open it. */
class DownloadedAttachment(
    val filename: String,
    val mimeType: String,
    val bytes: ByteArray,
)

/** Thin IMAP client over Jakarta/Angus Mail. Supports password and XOAUTH2 auth. */
@Singleton
class ImapClient @Inject constructor() {

    /** Connects and returns the account's folder names. Throws on failure. */
    suspend fun listFolders(params: ImapConnectionParams): List<String> = withContext(Dispatchers.IO) {
        withStore(params) { store ->
            store.defaultFolder.list("*").map { it.fullName }
        }
    }

    /** Fetches the most recent [limit] INBOX headers, newest first. */
    suspend fun fetchRecentInbox(params: ImapConnectionParams, limit: Int): List<FetchedMessage> =
        withContext(Dispatchers.IO) {
            withStore(params) { store ->
                val inbox = store.getFolder("INBOX")
                inbox.open(Folder.READ_ONLY)
                try {
                    val total = inbox.messageCount
                    if (total == 0) return@withStore emptyList()

                    val messages = inbox.getMessages(maxOf(1, total - limit + 1), total)
                    inbox.fetch(
                        messages,
                        FetchProfile().apply {
                            add(FetchProfile.Item.ENVELOPE)
                            add(FetchProfile.Item.FLAGS)
                            add(UIDFolder.FetchProfileItem.UID)
                        },
                    )
                    val uidFolder = inbox as UIDFolder
                    messages.reversed().map { it.toFetchedMessage(uidFolder) }
                } finally {
                    runCatching { inbox.close(false) }
                }
            }
        }

    /** Runs an IMAP SEARCH over the whole INBOX (subject/from/body) and returns matching headers. */
    suspend fun search(params: ImapConnectionParams, query: String, limit: Int): List<FetchedMessage> =
        withContext(Dispatchers.IO) {
            withStore(params) { store ->
                val inbox = store.getFolder("INBOX")
                inbox.open(Folder.READ_ONLY)
                try {
                    val term = OrTerm(arrayOf(SubjectTerm(query), FromStringTerm(query), BodyTerm(query)))
                    val matches = inbox.search(term).toList()
                    if (matches.isEmpty()) return@withStore emptyList()
                    val recent = if (matches.size > limit) matches.takeLast(limit) else matches
                    inbox.fetch(
                        recent.toTypedArray(),
                        FetchProfile().apply {
                            add(FetchProfile.Item.ENVELOPE)
                            add(FetchProfile.Item.FLAGS)
                            add(UIDFolder.FetchProfileItem.UID)
                        },
                    )
                    val uidFolder = inbox as UIDFolder
                    recent.reversed().map { it.toFetchedMessage(uidFolder) }
                } finally {
                    runCatching { inbox.close(false) }
                }
            }
        }

    /** Fetches a message body by UID and marks it \Seen on the server. */
    suspend fun fetchBodyMarkingSeen(params: ImapConnectionParams, uid: String): MessageContent =
        withContext(Dispatchers.IO) {
            withStore(params) { store ->
                val inbox = store.getFolder("INBOX")
                inbox.open(Folder.READ_WRITE)
                try {
                    val message = (inbox as UIDFolder).getMessageByUID(uid.toLong())
                        ?: error("Message $uid not found")
                    val content = (extractBody(message) ?: MessageContent("", isHtml = false))
                        .copy(attachments = collectAttachments(message))
                    message.setFlag(Flags.Flag.SEEN, true)
                    content
                } finally {
                    runCatching { inbox.close(false) }
                }
            }
        }

    /** Downloads the bytes of one attachment part (identified by its [partIndex]). */
    suspend fun fetchAttachment(params: ImapConnectionParams, uid: String, partIndex: Int): DownloadedAttachment =
        withContext(Dispatchers.IO) {
            withStore(params) { store ->
                val inbox = store.getFolder("INBOX")
                inbox.open(Folder.READ_ONLY)
                try {
                    val message = (inbox as UIDFolder).getMessageByUID(uid.toLong())
                        ?: error("Message $uid not found")
                    val parts = mutableListOf<Part>()
                    collectAttachmentParts(message, parts)
                    val part = parts.getOrNull(partIndex) ?: error("Attachment $partIndex not found")
                    val bytes = part.inputStream.use { it.readBytes() }
                    DownloadedAttachment(
                        filename = attachmentName(part) ?: "attachment",
                        mimeType = baseType(part),
                        bytes = bytes,
                    )
                } finally {
                    runCatching { inbox.close(false) }
                }
            }
        }

    suspend fun setFlag(params: ImapConnectionParams, uid: String, flag: Flags.Flag, value: Boolean) =
        withContext(Dispatchers.IO) {
            withStore(params) { store ->
                val inbox = store.getFolder("INBOX")
                inbox.open(Folder.READ_WRITE)
                try {
                    (inbox as UIDFolder).getMessageByUID(uid.toLong())?.setFlag(flag, value)
                } finally {
                    runCatching { inbox.close(false) }
                }
            }
        }

    suspend fun deleteMessage(params: ImapConnectionParams, uid: String) =
        withContext(Dispatchers.IO) {
            withStore(params) { store ->
                val inbox = store.getFolder("INBOX")
                inbox.open(Folder.READ_WRITE)
                try {
                    (inbox as UIDFolder).getMessageByUID(uid.toLong())?.setFlag(Flags.Flag.DELETED, true)
                    inbox.expunge()
                } finally {
                    runCatching { inbox.close(false) }
                }
            }
        }

    /**
     * Holds a long-lived IMAP connection and uses IMAP IDLE to wait for server activity. The
     * server pushes new-mail notifications while [IMAPFolder.idle] blocks; Jakarta dispatches them
     * to the message-count listener (not by returning from idle()), so we forward each push to
     * [onActivity] via a conflated channel. Runs until the coroutine is cancelled (which closes the
     * connection to unblock idle()) or a connection error is thrown, leaving reconnection to the caller.
     */
    suspend fun idle(params: ImapConnectionParams, onActivity: suspend () -> Unit) =
        withContext(Dispatchers.IO) {
            val protocol = if (params.security == MailSecurity.SSL_TLS) "imaps" else "imap"
            val store = Session.getInstance(buildProps(protocol, params)).getStore(protocol)
            store.connect(params.host, params.port, params.username, params.secret)
            val inbox = store.getFolder("INBOX") as IMAPFolder
            inbox.open(Folder.READ_ONLY)
            Log.d(TAG, "IDLE connected for ${params.username}")

            val pushes = Channel<Unit>(Channel.CONFLATED)
            inbox.addMessageCountListener(object : MessageCountAdapter() {
                override fun messagesAdded(event: MessageCountEvent) {
                    Log.d(TAG, "IDLE push: ${event.messages.size} new message(s)")
                    pushes.trySend(Unit)
                }
            })

            coroutineScope {
                val syncer = launch {
                    for (signal in pushes) onActivity()
                }
                // Close the connection the moment this scope is cancelled (renewal timeout or
                // service stop). Doing it here — at cancellation *start*, not job completion —
                // unblocks the blocking idle() read below so the loop exits promptly; a
                // completion handler would never run while idle() is still blocked.
                val closer = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) { runCatching { store.close() } }
                    }
                }
                // Sync once on connect to catch anything that arrived before IDLE was established.
                pushes.trySend(Unit)
                try {
                    while (isActive) {
                        inbox.idle()
                    }
                } catch (e: Exception) {
                    if (isActive) throw e // a real connection error: let the caller reconnect
                } finally {
                    closer.cancel()
                    syncer.cancel()
                    pushes.close()
                    runCatching { inbox.close(false) }
                    runCatching { store.close() }
                }
            }
        }

    /** Recursively finds the best body part: HTML preferred, plain text otherwise. */
    private fun extractBody(part: Part): MessageContent? {
        if (part.isMimeType("text/html")) return MessageContent(part.content.toString(), isHtml = true)
        if (part.isMimeType("text/plain")) return MessageContent(part.content.toString(), isHtml = false)
        if (part.isMimeType("multipart/*")) {
            val multipart = part.content as? Multipart ?: return null
            var plain: MessageContent? = null
            for (i in 0 until multipart.count) {
                val child = multipart.getBodyPart(i)
                if (Part.ATTACHMENT.equals(child.disposition, ignoreCase = true)) continue
                val result = extractBody(child) ?: continue
                if (result.isHtml) return result
                if (plain == null) plain = result
            }
            return plain
        }
        return null
    }

    /** Walks the MIME tree and returns attachment metadata in a stable, depth-first order. */
    private fun collectAttachments(message: Part): List<AttachmentPart> {
        val parts = mutableListOf<Part>()
        collectAttachmentParts(message, parts)
        return parts.mapIndexed { index, part ->
            AttachmentPart(
                partIndex = index,
                filename = attachmentName(part) ?: "attachment",
                mimeType = baseType(part),
                sizeBytes = part.size.toLong().coerceAtLeast(0L),
            )
        }
    }

    private fun collectAttachmentParts(part: Part, into: MutableList<Part>) {
        when {
            part.isMimeType("multipart/*") -> {
                val multipart = part.content as? Multipart ?: return
                for (i in 0 until multipart.count) collectAttachmentParts(multipart.getBodyPart(i), into)
            }
            isAttachment(part) -> into.add(part)
        }
    }

    private fun isAttachment(part: Part): Boolean =
        Part.ATTACHMENT.equals(part.disposition, ignoreCase = true) || !part.fileName.isNullOrBlank()

    private fun attachmentName(part: Part): String? =
        part.fileName?.let { runCatching { MimeUtility.decodeText(it) }.getOrDefault(it) }

    private fun baseType(part: Part): String =
        runCatching { ContentType(part.contentType).baseType }.getOrDefault("application/octet-stream")

    private fun Message.toFetchedMessage(uidFolder: UIDFolder): FetchedMessage {
        val from = from?.firstOrNull() as? InternetAddress
        return FetchedMessage(
            uid = uidFolder.getUID(this).toString(),
            sender = from?.personal ?: from?.address ?: "(unknown sender)",
            senderEmail = from?.address.orEmpty(),
            subject = subject ?: "(no subject)",
            timestampMillis = (sentDate ?: receivedDate)?.time ?: System.currentTimeMillis(),
            isRead = isSet(Flags.Flag.SEEN),
            isFlagged = isSet(Flags.Flag.FLAGGED),
        )
    }

    private inline fun <T> withStore(params: ImapConnectionParams, block: (Store) -> T): T {
        val protocol = if (params.security == MailSecurity.SSL_TLS) "imaps" else "imap"
        val store = Session.getInstance(buildProps(protocol, params)).getStore(protocol)
        store.connect(params.host, params.port, params.username, params.secret)
        return try {
            block(store)
        } finally {
            runCatching { store.close() }
        }
    }

    private fun buildProps(protocol: String, params: ImapConnectionParams): Properties = Properties().apply {
        put("mail.store.protocol", protocol)
        put("mail.$protocol.host", params.host)
        put("mail.$protocol.port", params.port.toString())
        put("mail.$protocol.connectiontimeout", TIMEOUT_MS)
        put("mail.$protocol.timeout", TIMEOUT_MS)
        put("mail.$protocol.writetimeout", TIMEOUT_MS)
        if (params.security == MailSecurity.STARTTLS) {
            put("mail.$protocol.starttls.enable", "true")
            put("mail.$protocol.starttls.required", "true")
        }
        if (params.useXoauth2) {
            put("mail.$protocol.auth.mechanisms", "XOAUTH2")
        }
    }

    private companion object {
        const val TIMEOUT_MS = "15000"
        const val TAG = "LibreMailIdle"
    }
}
