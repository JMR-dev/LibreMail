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

/** A folder (mailbox) listed from the server, with the metadata needed to classify and display it. */
data class FetchedFolder(
    val fullName: String,
    val displayName: String,
    /** Raw IMAP attributes from the LIST response (RFC 6154 SPECIAL-USE flags, \Noselect, …). */
    val attributes: List<String>,
    /** False for \Noselect containers (e.g. Gmail's "[Gmail]" parent) that can't be opened. */
    val selectable: Boolean,
)

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

    /** Connects and returns the account's folders with their SPECIAL-USE attributes. Throws on failure. */
    suspend fun listFolders(params: ImapConnectionParams): List<FetchedFolder> = withContext(Dispatchers.IO) {
        withStore(params) { store ->
            val folders = store.defaultFolder.list("*").map { folder ->
                val attributes = if (folder is IMAPFolder) {
                    runCatching { folder.attributes.toList() }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                val separator = runCatching { folder.separator }.getOrDefault('/')
                FetchedFolder(
                    fullName = folder.fullName,
                    displayName = folder.fullName.substringAfterLast(separator),
                    attributes = attributes,
                    selectable = attributes.none { it.equals("\\Noselect", ignoreCase = true) },
                )
            }
            // Some servers don't return INBOX from a wildcard LIST; guarantee it's always present.
            if (folders.none { it.fullName.equals("INBOX", ignoreCase = true) }) {
                listOf(FetchedFolder("INBOX", "INBOX", emptyList(), selectable = true)) + folders
            } else {
                folders
            }
        }
    }

    /** Fetches the most recent [limit] headers of [folder], newest first. */
    suspend fun fetchRecent(params: ImapConnectionParams, folder: String, limit: Int): List<FetchedMessage> =
        withContext(Dispatchers.IO) {
            withStore(params) { store ->
                val mailbox = store.getFolder(folder)
                mailbox.open(Folder.READ_ONLY)
                try {
                    val total = mailbox.messageCount
                    if (total == 0) return@withStore emptyList()

                    val messages = mailbox.getMessages(maxOf(1, total - limit + 1), total)
                    mailbox.fetch(
                        messages,
                        FetchProfile().apply {
                            add(FetchProfile.Item.ENVELOPE)
                            add(FetchProfile.Item.FLAGS)
                            add(UIDFolder.FetchProfileItem.UID)
                        },
                    )
                    val uidFolder = mailbox as UIDFolder
                    messages.reversed().map { it.toFetchedMessage(uidFolder) }
                } finally {
                    runCatching { mailbox.close(false) }
                }
            }
        }

    /** Runs an IMAP SEARCH over [folder] (subject/from/body) and returns matching headers. */
    suspend fun search(params: ImapConnectionParams, folder: String, query: String, limit: Int): List<FetchedMessage> =
        withContext(Dispatchers.IO) {
            withStore(params) { store ->
                val mailbox = store.getFolder(folder)
                mailbox.open(Folder.READ_ONLY)
                try {
                    val term = OrTerm(arrayOf(SubjectTerm(query), FromStringTerm(query), BodyTerm(query)))
                    val matches = mailbox.search(term).toList()
                    if (matches.isEmpty()) return@withStore emptyList()
                    val recent = if (matches.size > limit) matches.takeLast(limit) else matches
                    mailbox.fetch(
                        recent.toTypedArray(),
                        FetchProfile().apply {
                            add(FetchProfile.Item.ENVELOPE)
                            add(FetchProfile.Item.FLAGS)
                            add(UIDFolder.FetchProfileItem.UID)
                        },
                    )
                    val uidFolder = mailbox as UIDFolder
                    recent.reversed().map { it.toFetchedMessage(uidFolder) }
                } finally {
                    runCatching { mailbox.close(false) }
                }
            }
        }

    /** Fetches a message body by UID from [folder] and marks it \Seen on the server. */
    suspend fun fetchBodyMarkingSeen(params: ImapConnectionParams, folder: String, uid: String): MessageContent =
        withContext(Dispatchers.IO) {
            withStore(params) { store ->
                val mailbox = store.getFolder(folder)
                mailbox.open(Folder.READ_WRITE)
                try {
                    val message = (mailbox as UIDFolder).getMessageByUID(uid.toLong())
                        ?: error("Message $uid not found")
                    val content = (extractBody(message) ?: MessageContent("", isHtml = false))
                        .copy(attachments = collectAttachments(message))
                    message.setFlag(Flags.Flag.SEEN, true)
                    content
                } finally {
                    runCatching { mailbox.close(false) }
                }
            }
        }

    /** Downloads the bytes of one attachment part (identified by its [partIndex]) from [folder]. */
    suspend fun fetchAttachment(
        params: ImapConnectionParams,
        folder: String,
        uid: String,
        partIndex: Int,
    ): DownloadedAttachment =
        withContext(Dispatchers.IO) {
            withStore(params) { store ->
                val mailbox = store.getFolder(folder)
                mailbox.open(Folder.READ_ONLY)
                try {
                    val message = (mailbox as UIDFolder).getMessageByUID(uid.toLong())
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
                    runCatching { mailbox.close(false) }
                }
            }
        }

    suspend fun setFlag(params: ImapConnectionParams, folder: String, uid: String, flag: Flags.Flag, value: Boolean) =
        withContext(Dispatchers.IO) {
            withStore(params) { store ->
                val mailbox = store.getFolder(folder)
                mailbox.open(Folder.READ_WRITE)
                try {
                    (mailbox as UIDFolder).getMessageByUID(uid.toLong())?.setFlag(flag, value)
                } finally {
                    runCatching { mailbox.close(false) }
                }
            }
        }

    suspend fun deleteMessage(params: ImapConnectionParams, folder: String, uid: String) =
        withContext(Dispatchers.IO) {
            withStore(params) { store ->
                val mailbox = store.getFolder(folder)
                mailbox.open(Folder.READ_WRITE)
                try {
                    (mailbox as UIDFolder).getMessageByUID(uid.toLong())?.setFlag(Flags.Flag.DELETED, true)
                    mailbox.expunge()
                } finally {
                    runCatching { mailbox.close(false) }
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
            // Close the just-connected store if opening the folder fails, so a failed connect in the
            // IDLE reconnect loop can't leak connections until the server's per-account limit is hit.
            val inbox = try {
                (store.getFolder("INBOX") as IMAPFolder).also { it.open(Folder.READ_ONLY) }
            } catch (e: Throwable) {
                runCatching { store.close() }
                throw e
            }
            Log.d(TAG, "IDLE connected")

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
            put("mail.$protocol.starttls.required", params.strictStartTls.toString())
        }
        // Verify the server certificate matches the host whenever TLS is used. Angus already
        // defaults this to true; set it explicitly so a future library-default change can't
        // silently disable hostname checking and expose us to MITM. (No-op for MailSecurity.NONE.)
        put("mail.$protocol.ssl.checkserveridentity", "true")
        if (params.useXoauth2) {
            put("mail.$protocol.auth.mechanisms", "XOAUTH2")
        }
    }

    private companion object {
        const val TIMEOUT_MS = "15000"
        const val TAG = "LibreMailIdle"
    }
}
