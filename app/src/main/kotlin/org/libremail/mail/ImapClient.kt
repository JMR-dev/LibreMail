// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail

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
import jakarta.mail.internet.MimePart
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
import org.libremail.reporting.AppLog
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger
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

/**
 * Metadata for one downloadable part. [partIndex] is its position in attachment-tree order.
 * [contentId] is the normalized `Content-ID` (angle brackets stripped) for an inline image referenced
 * from the HTML body via `cid:` — null for an ordinary attachment. Inline parts are persisted so the
 * reader can resolve `cid:` requests, but excluded from the user-facing attachment list.
 */
data class AttachmentPart(
    val partIndex: Int,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val contentId: String? = null,
)

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
        withStore(params, op = "backfill-page") { store ->
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
            withStore(params, op = "body-fetch") { store ->
                val mailbox = store.getFolder(folder)
                val selectStart = System.nanoTime()
                mailbox.open(Folder.READ_WRITE)
                val selectMs = (System.nanoTime() - selectStart) / NANOS_PER_MS
                try {
                    val message = (mailbox as UIDFolder).getMessageByUID(uid.toLong())
                        ?: error("Message $uid not found")
                    val fetchStart = System.nanoTime()
                    val content = (extractBody(message) ?: MessageContent("", isHtml = false))
                        .copy(attachments = collectAttachments(message))
                    val fetchMs = (System.nanoTime() - fetchStart) / NANOS_PER_MS
                    // RFC822.SIZE (server-reported wire size) is the download-budget signal; body char
                    // count and attachment count round it out. All are numbers — never message content.
                    val rfc822Bytes = runCatching { message.size }.getOrDefault(-1)
                    val flagStart = System.nanoTime()
                    message.setFlag(Flags.Flag.SEEN, true)
                    val flagMs = (System.nanoTime() - flagStart) / NANOS_PER_MS
                    AppLog.d(
                        PERF_TAG,
                        "body-fetch select=${selectMs}ms body=${fetchMs}ms flag=${flagMs}ms " +
                            "rfc822=${rfc822Bytes}B chars=${content.body.length} att=${content.attachments.size}",
                    )
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
            withStore(params, op = "prefetch-body") { store ->
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
        withStore(params, op = "attachment") { store ->
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
            withStore(params, op = "flag") { store ->
                val mailbox = store.getFolder(folder)
                mailbox.open(Folder.READ_WRITE)
                try {
                    (mailbox as UIDFolder).getMessageByUID(uid.toLong())?.setFlag(flag, value)
                } finally {
                    runCatching { mailbox.close(false) }
                }
            }
        }

    /**
     * Deletes a single message by UID. Delegates to [deleteMessages] so even a lone delete uses the
     * same **targeted** UID EXPUNGE — never the untargeted expunge that would also remove other
     * `\Deleted`-flagged mail in the folder (issue #295).
     */
    suspend fun deleteMessage(params: ImapConnectionParams, folder: String, uid: String) =
        deleteMessages(params, folder, listOf(uid))

    /**
     * Permanently deletes the messages with [uids] from [folder] in a **single** IMAP session: opens
     * the folder once, flags the matched messages `\Deleted`, then issues one targeted UID EXPUNGE via
     * [expungeTargeted]. This is the batch, data-safe path the repository's multi-message delete/expunge
     * routes through — one login + one expunge for the whole selection, instead of the N logins + N
     * expunges a per-UID [deleteMessage] loop would pay, and it never touches unrelated `\Deleted` mail
     * (issue #295). A no-op when [uids] is empty or none of them resolve to a message in [folder].
     */
    suspend fun deleteMessages(params: ImapConnectionParams, folder: String, uids: List<String>) =
        withContext(Dispatchers.IO) {
            if (uids.isEmpty()) return@withContext
            withStore(params) { store ->
                val mailbox = store.getFolder(folder)
                mailbox.open(Folder.READ_WRITE)
                try {
                    val uidFolder = mailbox as UIDFolder
                    val messages = uids.mapNotNull { uidFolder.getMessageByUID(it.toLong()) }.toTypedArray()
                    if (messages.isEmpty()) return@withStore
                    mailbox.setFlags(messages, Flags(Flags.Flag.DELETED), true)
                    expungeTargeted(mailbox, messages)
                } finally {
                    runCatching { mailbox.close(false) }
                }
            }
        }

    /**
     * Moves messages from [sourceFolder] to [destFolder] by UID. Implemented as copy + \Deleted +
     * targeted expunge so it works on every IMAP server (no reliance on the RFC 6851 MOVE extension).
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
                expungeTargeted(mailbox, messages)
            } finally {
                runCatching { mailbox.close(false) }
            }
        }
    }

    /**
     * Permanently removes exactly [messages] — which the caller has already flagged `\Deleted` — with a
     * **targeted** UID EXPUNGE (RFC 4315, [IMAPFolder.expunge]). Deliberately never the untargeted
     * [Folder.expunge], which expunges *every* `\Deleted`-flagged message in [mailbox] — including ones
     * a second client, Gmail, or a partial earlier move left flagged — the data-loss bug in issue #295.
     * Every folder this client opens is an [IMAPFolder], so the cast holds in production and under
     * GreenMail. The server must advertise UIDPLUS (Gmail, Outlook, and GreenMail do); on one that does
     * not, Angus raises "UID EXPUNGE not supported" here rather than silently falling back to the
     * unrelated-mail-destroying untargeted expunge — a loud failure is the correct, safe outcome.
     */
    private fun expungeTargeted(mailbox: Folder, messages: Array<Message>) {
        (mailbox as IMAPFolder).expunge(messages)
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
        AppLog.d(TAG, "IDLE connected")

        val pushes = Channel<Unit>(Channel.CONFLATED)
        inbox.addMessageCountListener(object : MessageCountAdapter() {
            override fun messagesAdded(event: MessageCountEvent) {
                AppLog.d(TAG, "IDLE push: ${event.messages.size} new message(s)")
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

    /**
     * Walks the MIME tree and returns downloadable-part metadata in a stable, depth-first order.
     * Inline images (a `Content-ID` referenced from the HTML via `cid:`) are included so their
     * bytes can be fetched by [partIndex] and resolved by the reader, but each carries its
     * [AttachmentPart.contentId] so the display layer can filter them out of the attachment list.
     */
    private fun collectAttachments(message: Part): List<AttachmentPart> {
        val parts = mutableListOf<Part>()
        collectAttachmentParts(message, parts)
        return parts.mapIndexed { index, part ->
            AttachmentPart(
                partIndex = index,
                filename = attachmentName(part) ?: "attachment",
                mimeType = baseType(part),
                sizeBytes = part.size.toLong().coerceAtLeast(0L),
                contentId = if (isInlineImagePart(part)) inlineContentId(part) else null,
            )
        }
    }

    private fun collectAttachmentParts(part: Part, into: MutableList<Part>) {
        when {
            part.isMimeType("multipart/*") -> {
                val multipart = part.content as? Multipart ?: return
                for (i in 0 until multipart.count) collectAttachmentParts(multipart.getBodyPart(i), into)
            }
            isAttachmentPart(part) || isInlineImagePart(part) -> into.add(part)
        }
    }

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
     * Live count of connect-per-operation IMAP connections currently open through [withStore] (this
     * excludes the single long-lived IDLE connection, which does not go through here). Logged per op so
     * a debug report can show how close we run to a provider's simultaneous-connection ceiling — Gmail
     * allows 15 — under concurrent backfill + interactive load.
     */
    private val liveConnectionCount = AtomicInteger(0)

    /**
     * Runs [block] against a connected [Store]. With the reuse flag OFF (default) this is the original
     * behaviour: a fresh, authenticated connection per call, torn down in `finally`. With it ON, the
     * call borrows a kept-alive per-account connection from [connectionCache] (established once, reused
     * across folder-opens) instead — see issue #125.
     *
     * [op] is a short, PII-free label for the caller's intent (e.g. `body-fetch`, `backfill-page`) used
     * only in the perf breadcrumb below, so a debug report can attribute latency to connection
     * establishment (`connect`) vs the operation's own server work (`work`).
     */
    private suspend fun <T> withStore(params: ImapConnectionParams, op: String = "imap", block: (Store) -> T): T {
        val cache = connectionCache
        return if (cache != null) {
            cache.withStore(params, block)
        } else {
            // Time CONNECT + TLS + LOGIN separately from the op's own work, and record how many
            // connect-per-op sockets are live at once, so a slow op can be attributed and the provider
            // connection ceiling observed. Logged in `finally` so a thrown/timed-out op is captured too.
            val connectStart = System.nanoTime()
            val store = openConnectedStore(params)
            val connectMs = (System.nanoTime() - connectStart) / NANOS_PER_MS
            val live = liveConnectionCount.incrementAndGet()
            val workStart = System.nanoTime()
            try {
                block(store)
            } finally {
                val workMs = (System.nanoTime() - workStart) / NANOS_PER_MS
                AppLog.d(PERF_TAG, "$op connect=${connectMs}ms work=${workMs}ms live=$live")
                runCatching { store.close() }
                liveConnectionCount.decrementAndGet()
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
        const val PERF_TAG = "ImapPerf"
        const val NANOS_PER_MS = 1_000_000L
    }
}

/**
 * True when [part] is a user-facing downloadable attachment: its `Content-Disposition` is
 * `attachment`, OR it has a filename but no `Content-ID` header. A part with a filename AND a
 * `Content-ID` (an inline image carried by `Content-Disposition: inline`) is deliberately NOT an
 * attachment — it belongs in the message body, not the attachment list (issue #133).
 */
internal fun isAttachmentPart(part: Part): Boolean = Part.ATTACHMENT.equals(part.disposition, ignoreCase = true) ||
    (!part.fileName.isNullOrBlank() && inlineContentId(part) == null)

/**
 * True when [part] is an inline image embedded in the HTML body and referenced from it via
 * `cid:<Content-ID>` (e.g. a USPS Informed Delivery digest's mail-piece thumbnails): it has a
 * `Content-ID`, is an image, and is not already an [isAttachmentPart]. Such parts are excluded from
 * the attachment list and instead served to the reader's WebView by their Content-ID.
 */
internal fun isInlineImagePart(part: Part): Boolean =
    inlineContentId(part) != null && part.isMimeType("image/*") && !isAttachmentPart(part)

/**
 * The normalized `Content-ID` of [part] (surrounding angle brackets stripped), or null if it has
 * none. Reads it via [MimePart.getContentID] rather than `getHeader("Content-ID")`: over IMAP the
 * Content-ID comes from the already-fetched BODYSTRUCTURE, whereas a raw header lookup would force
 * (and often miss on) a separate per-part MIME-header fetch.
 */
internal fun inlineContentId(part: Part): String? = runCatching { (part as? MimePart)?.contentID }.getOrNull()
    ?.trim()?.trim('<', '>')?.trim()?.takeUnless { it.isBlank() }
