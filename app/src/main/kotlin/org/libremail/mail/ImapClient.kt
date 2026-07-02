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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPMessage
import org.libremail.domain.model.ImapConnectionParams
import org.libremail.domain.model.MailSecurity
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton

/** A folder (mailbox) listed from the server, with the metadata needed to classify and display it. */
data class FetchedFolder(
    val fullName: String,
    val displayName: String,
    /** Raw IMAP attributes from the LIST response (RFC 6154 SPECIAL-USE flags, \Noselect, …). */
    val attributes: List<String>,
    /** False for \Noselect containers (e.g. Gmail's "[Gmail]" parent) that can't be opened. */
    val selectable: Boolean,
    /**
     * The hierarchy separator the server reported for this folder in its LIST response (e.g. '/' for
     * Gmail, '.' for some servers). Null when the server reported none (a flat namespace) or it could
     * not be read. Persisted so a folder's parent is split on the authoritative delimiter rather than
     * one re-inferred from the name (issue #66).
     */
    val hierarchyDelimiter: Char? = null,
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
data class MessageContent(val body: String, val isHtml: Boolean, val attachments: List<AttachmentPart> = emptyList())

/** Metadata for one attachment part. [partIndex] is its position in attachment-tree order. */
data class AttachmentPart(val partIndex: Int, val filename: String, val mimeType: String, val sizeBytes: Long)

/** A downloaded attachment's bytes plus the metadata needed to open it. */
class DownloadedAttachment(val filename: String, val mimeType: String, val bytes: ByteArray)

/** The original message's fields needed to compose a reply, reply-all, or forward. */
data class ReplyContext(
    val fromEmail: String,
    val toRecipients: List<String>,
    val ccRecipients: List<String>,
    val subject: String,
    val sentDateMillis: Long,
    val body: String,
    val isHtml: Boolean,
)

/** Thin IMAP client over Jakarta/Angus Mail. Supports password and XOAUTH2 auth. */
@Singleton
class ImapClient(private val reuseConnections: Boolean) {

    /**
     * Production entry point. Connection reuse is a SPIKE flag (issue #125), **OFF by default** so it
     * cannot destabilize the connect-per-operation behaviour on `main`: with it off, [withStore] is
     * byte-for-byte today's connect + LOGOUT-per-call. Once real-device validation (see
     * `docs/perf/issue-125-connection-reuse-spike.md`) confirms the win, wire this to a setting or
     * `BuildConfig`; today only the reuse harness flips it on via the primary constructor.
     */
    @Inject constructor() : this(reuseConnections = false)

    /**
     * Per-account keep-alive cache; allocated only when the spike flag is on, so the default build
     * carries neither the state nor the reuse code path.
     */
    private val connectionCache: ImapConnectionCache? =
        if (reuseConnections) ImapConnectionCache(::openConnectedStore) else null

    /** Connects and returns the account's folders with their SPECIAL-USE attributes. Throws on failure. */
    suspend fun listFolders(params: ImapConnectionParams): List<FetchedFolder> = withContext(Dispatchers.IO) {
        withStore(params) { store ->
            val folders = store.defaultFolder.list("*").map { folder ->
                val attributes = if (folder is IMAPFolder) {
                    runCatching { folder.attributes.toList() }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                val reportedSeparator = runCatching { folder.separator }.getOrNull()
                // Fall back to '/' only for splitting off the display name; the persisted delimiter
                // stays null when the server reported none, so parentOf can tell "unknown" (infer)
                // from a real separator.
                val separator = reportedSeparator ?: '/'
                FetchedFolder(
                    fullName = folder.fullName,
                    displayName = folder.fullName.substringAfterLast(separator),
                    attributes = attributes,
                    selectable = attributes.none { it.equals("\\Noselect", ignoreCase = true) },
                    hierarchyDelimiter = reportedSeparator?.takeUnless { it == Char.MIN_VALUE },
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

    /**
     * Fetches up to [limit] headers of [folder] immediately older than [beforeUid] (i.e. with a
     * server UID strictly less than it), newest-first — the backwards page used by the full-history
     * backfill (issue #12). Pass [Long.MAX_VALUE] to start from the newest message.
     *
     * Bounded in both memory and network cost: the boundary message number is located with a
     * binary search over message numbers by UID (UIDs increase monotonically with message number),
     * costing O(log n) tiny `UID FETCH` round trips, and only the [limit]-sized batch is materialized.
     * Returns empty when nothing older exists, which the caller treats as "folder fully backfilled".
     */
    suspend fun fetchOlderThan(
        params: ImapConnectionParams,
        folder: String,
        beforeUid: Long,
        limit: Int,
    ): List<FetchedMessage> = withContext(Dispatchers.IO) {
        withStore(params) { store ->
            val mailbox = store.getFolder(folder)
            mailbox.open(Folder.READ_ONLY)
            try {
                val total = mailbox.messageCount
                if (total == 0 || beforeUid <= 1L) return@withStore emptyList()
                val uidFolder = mailbox as UIDFolder
                // Highest message number whose UID < beforeUid (0 when nothing is older).
                val boundary = highestMessageNumberBelowUid(mailbox, uidFolder, total, beforeUid)
                if (boundary == 0) return@withStore emptyList()

                val start = maxOf(1, boundary - limit + 1)
                val messages = mailbox.getMessages(start, boundary)
                mailbox.fetch(
                    messages,
                    FetchProfile().apply {
                        add(FetchProfile.Item.ENVELOPE)
                        add(FetchProfile.Item.FLAGS)
                        add(UIDFolder.FetchProfileItem.UID)
                    },
                )
                messages.reversed().map { it.toFetchedMessage(uidFolder) }
            } finally {
                runCatching { mailbox.close(false) }
            }
        }
    }

    /**
     * Binary-searches message numbers `1..total` for the highest one whose UID is `< beforeUid`.
     * Robust to expunges (it reads live UIDs), so it works even if the message that had exactly
     * [beforeUid] has since been removed. Returns 0 when every message's UID is `>= beforeUid`.
     */
    private fun highestMessageNumberBelowUid(mailbox: Folder, uidFolder: UIDFolder, total: Int, beforeUid: Long): Int {
        var lo = 1
        var hi = total
        var boundary = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val midUid = uidFolder.getUID(mailbox.getMessage(mid))
            if (midUid < beforeUid) {
                boundary = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return boundary
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

    /**
     * Fetches a message body + attachment metadata by UID **without** marking it \Seen (opens READ_ONLY
     * and uses BODY.PEEK). Used to pre-cache content during sync so opening the message is instant.
     */
    suspend fun fetchBodyPeek(params: ImapConnectionParams, folder: String, uid: String): MessageContent =
        withContext(Dispatchers.IO) {
            withStore(params) { store ->
                val mailbox = store.getFolder(folder)
                mailbox.open(Folder.READ_ONLY)
                try {
                    val message = (mailbox as UIDFolder).getMessageByUID(uid.toLong())
                        ?: error("Message $uid not found")
                    (message as? IMAPMessage)?.setPeek(true)
                    (extractBody(message) ?: MessageContent("", isHtml = false))
                        .copy(attachments = collectAttachments(message))
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
    ): DownloadedAttachment = withContext(Dispatchers.IO) {
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

    suspend fun deleteMessage(params: ImapConnectionParams, folder: String, uid: String) = withContext(Dispatchers.IO) {
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
     * Moves messages from [sourceFolder] to [destFolder] by UID. Implemented as copy + \Deleted +
     * expunge so it works on every IMAP server (no reliance on the RFC 6851 MOVE extension).
     */
    suspend fun moveMessages(
        params: ImapConnectionParams,
        sourceFolder: String,
        uids: List<String>,
        destFolder: String,
    ) = withContext(Dispatchers.IO) {
        if (uids.isEmpty()) return@withContext
        withStore(params) { store ->
            val mailbox = store.getFolder(sourceFolder)
            mailbox.open(Folder.READ_WRITE)
            try {
                val uidFolder = mailbox as UIDFolder
                val messages = uids.mapNotNull { uidFolder.getMessageByUID(it.toLong()) }.toTypedArray()
                if (messages.isEmpty()) return@withStore
                mailbox.copyMessages(messages, store.getFolder(destFolder))
                mailbox.setFlags(messages, Flags(Flags.Flag.DELETED), true)
                mailbox.expunge()
            } finally {
                runCatching { mailbox.close(false) }
            }
        }
    }

    /**
     * Fetches the fields needed to compose a reply/forward (recipients + body) without marking the
     * original \Seen (opens READ_ONLY), so building a reply doesn't change the message's read state.
     */
    suspend fun fetchForReply(params: ImapConnectionParams, folder: String, uid: String): ReplyContext =
        withContext(Dispatchers.IO) {
            withStore(params) { store ->
                val mailbox = store.getFolder(folder)
                mailbox.open(Folder.READ_ONLY)
                try {
                    val message = (mailbox as UIDFolder).getMessageByUID(uid.toLong())
                        ?: error("Message $uid not found")
                    // BODY.PEEK: reading the body to quote it must not set the \Seen flag.
                    (message as? IMAPMessage)?.setPeek(true)
                    val from = message.from?.firstOrNull() as? InternetAddress
                    val content = extractBody(message) ?: MessageContent("", isHtml = false)
                    ReplyContext(
                        fromEmail = from?.address.orEmpty(),
                        toRecipients = message.recipientEmails(Message.RecipientType.TO),
                        ccRecipients = message.recipientEmails(Message.RecipientType.CC),
                        subject = message.subject ?: "",
                        sentDateMillis = (message.sentDate ?: message.receivedDate)?.time
                            ?: System.currentTimeMillis(),
                        body = content.body,
                        isHtml = content.isHtml,
                    )
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
    suspend fun idle(params: ImapConnectionParams, onActivity: suspend () -> Unit) = withContext(Dispatchers.IO) {
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
    private fun extractBody(part: Part): MessageContent? = when {
        part.isMimeType("text/html") -> MessageContent(part.content.toString(), isHtml = true)
        part.isMimeType("text/plain") -> MessageContent(part.content.toString(), isHtml = false)
        part.isMimeType("multipart/*") -> extractFromMultipart(part)
        else -> null
    }

    /** The best sub-part of a multipart body: the first HTML part, else the first plain-text part. */
    private fun extractFromMultipart(part: Part): MessageContent? {
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

    private fun attachmentName(part: Part): String? = part.fileName?.let {
        runCatching { MimeUtility.decodeText(it) }.getOrDefault(it)
    }

    private fun baseType(part: Part): String = runCatching {
        ContentType(part.contentType).baseType
    }.getOrDefault("application/octet-stream")

    /** The email addresses of the given recipient type, skipping any that aren't internet addresses. */
    private fun Message.recipientEmails(type: Message.RecipientType): List<String> = (
        getRecipients(type)
            ?: emptyArray()
        ).mapNotNull { (it as? InternetAddress)?.address }

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

    /**
     * Runs [block] against a connected [Store]. With the reuse flag OFF (default) this is the original
     * behaviour: a fresh, authenticated connection per call, torn down in `finally`. With it ON, the
     * call borrows a kept-alive per-account connection from [connectionCache] (established once, reused
     * across folder-opens) instead — see issue #125.
     */
    private suspend fun <T> withStore(params: ImapConnectionParams, block: (Store) -> T): T {
        val cache = connectionCache
        return if (cache != null) {
            cache.withStore(params, block)
        } else {
            val store = openConnectedStore(params)
            try {
                block(store)
            } finally {
                runCatching { store.close() }
            }
        }
    }

    /** Builds and authenticates a fresh [Store] (`CONNECT + TLS + LOGIN`); the caller owns closing it. */
    private fun openConnectedStore(params: ImapConnectionParams): Store {
        val protocol = if (params.security == MailSecurity.SSL_TLS) "imaps" else "imap"
        val store = Session.getInstance(buildProps(protocol, params, reuse = reuseConnections)).getStore(protocol)
        store.connect(params.host, params.port, params.username, params.secret)
        return store
    }

    /**
     * SPIKE hook (issue #125): closes any kept-alive reused connections (`LOGOUT` + teardown), a no-op
     * when the reuse flag is OFF. The reuse harness calls this to force settlement; a shipped feature
     * would also drive it from an idle-eviction timer and the low-battery push teardown (#88/#89/#90).
     */
    suspend fun closeReusedConnections() {
        connectionCache?.closeAll()
    }

    private fun buildProps(protocol: String, params: ImapConnectionParams, reuse: Boolean = false): Properties =
        Properties().apply {
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
            if (reuse) {
                // SPIKE (issue #125): pin a reused Store to exactly one authenticated socket. The
                // per-account mutex already serializes access, so one pooled connection suffices — and
                // capping it here makes "1 connection for N opens" the literal, provable invariant.
                put("mail.$protocol.connectionpoolsize", "1")
                put("mail.$protocol.separatestoreconnection", "false")
            }
        }

    private companion object {
        const val TIMEOUT_MS = "15000"
        const val TAG = "LibreMailIdle"
    }
}
