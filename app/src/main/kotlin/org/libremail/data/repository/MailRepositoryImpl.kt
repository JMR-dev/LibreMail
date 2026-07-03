// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.repository

import android.content.Context
import android.net.Uri
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.map
import dagger.hilt.android.qualifiers.ApplicationContext
import jakarta.mail.Flags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.libremail.data.ReplyBuilder
import org.libremail.data.SignatureBlock
import org.libremail.data.Snippet
import org.libremail.data.attachment.AttachmentUriGrants
import org.libremail.data.attachmentCacheDir
import org.libremail.data.local.dao.AccountDao
import org.libremail.data.local.dao.AttachmentDao
import org.libremail.data.local.dao.DraftDao
import org.libremail.data.local.dao.FolderDao
import org.libremail.data.local.dao.MessageDao
import org.libremail.data.local.dao.OutboxDao
import org.libremail.data.local.entity.FolderEntity
import org.libremail.data.local.entity.MessageRouting
import org.libremail.data.local.entity.MessageSummary
import org.libremail.data.local.entity.OutboxEntity
import org.libremail.data.local.toDomain
import org.libremail.data.local.toEntity
import org.libremail.data.local.toOutgoingAttachments
import org.libremail.data.local.toOutgoingAttachmentsJson
import org.libremail.data.settings.AccountSettingsRepository
import org.libremail.data.settings.SignatureRepository
import org.libremail.data.sync.MailConnectionFactory
import org.libremail.data.sync.SendScheduler
import org.libremail.domain.model.Attachment
import org.libremail.domain.model.Draft
import org.libremail.domain.model.Folder
import org.libremail.domain.model.FolderRole
import org.libremail.domain.model.ImapConnectionParams
import org.libremail.domain.model.InlineImage
import org.libremail.domain.model.Message
import org.libremail.domain.model.OutboxMessage
import org.libremail.domain.model.OutgoingAttachment
import org.libremail.domain.model.OutgoingMessage
import org.libremail.domain.model.ReplyMode
import org.libremail.domain.model.UnreadCount
import org.libremail.domain.model.sanitizeAttachmentName
import org.libremail.domain.repository.MailRepository
import org.libremail.mail.ImapClient
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MailRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messageDao: MessageDao,
    private val accountDao: AccountDao,
    private val attachmentDao: AttachmentDao,
    private val outboxDao: OutboxDao,
    private val draftDao: DraftDao,
    private val folderDao: FolderDao,
    private val imapClient: ImapClient,
    private val connectionFactory: MailConnectionFactory,
    private val sendScheduler: SendScheduler,
    private val accountSettingsRepository: AccountSettingsRepository,
    private val signatureRepository: SignatureRepository,
    private val attachmentUriGrants: AttachmentUriGrants,
) : MailRepository {

    // Application-lifetime scope for fire-and-forget server pushes that must outlive the caller — e.g.
    // openMessage() returning to the reader screen before the SEEN flag reaches the server (#148). Same
    // pattern as LibreMailApplication.appScope / IdleService.scope: this class is @Singleton (bound to
    // Hilt's SingletonComponent), so the scope's lifetime is the process's, not any one caller's coroutine.
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun pagedUnifiedFolderMessages(folder: String): Flow<PagingData<Message>> = Pager(
        config = PagingConfig(
            // A page comfortably exceeds a screenful so scrolling rarely waits on a load; loading
            // three pages up front fills the first viewport without a visible gap. Placeholders
            // are off: the row height varies (snippet/account label), so a fixed-height placeholder
            // would jump, and the list never needs a scrollbar sized to the full (uncounted) inbox.
            pageSize = MAILBOX_PAGE_SIZE,
            initialLoadSize = MAILBOX_PAGE_SIZE * 3,
            enablePlaceholders = false,
            // Bound the in-memory window so a deep scroll can't accumulate the whole (potentially
            // thousands-of-rows) inbox: keep ~5 pages resident and evict the rest. Must be
            // >= pageSize + 2*prefetchDistance (40 + 2*40 = 120); with placeholders off, evicted leading
            // positions drop from the loaded window and the list rendering already null-guards them.
            maxSize = MAILBOX_PAGE_SIZE * 5,
        ),
        pagingSourceFactory = { messageDao.pagingUnifiedFolderSummaries(folder) },
    ).flow.map { page -> page.map { it.toDomain() } }

    override fun pagedFolderMessages(accountId: String, folder: String): Flow<PagingData<Message>> =
        mailboxPager { messageDao.pagingFolderSummaries(accountId, folder) }

    override fun pagedUnifiedSearchMessages(folder: String, query: String): Flow<PagingData<Message>> =
        mailboxPager { messageDao.pagingUnifiedFolderSearchSummaries(folder, likePattern(query)) }

    override fun pagedFolderSearchMessages(
        accountId: String,
        folder: String,
        query: String,
    ): Flow<PagingData<Message>> =
        mailboxPager { messageDao.pagingFolderSearchSummaries(accountId, folder, likePattern(query)) }

    /**
     * Shared [Pager] for the per-account and search mailbox lists (issue #214). Same window sizing as
     * the unified browse pager, plus a bounded `maxSize` so scrolling a long list drops far-offscreen
     * pages instead of retaining the whole scrolled-through range in memory. Placeholders stay off (the
     * row height varies, and the list never sizes a scrollbar to the full uncounted result).
     */
    private fun mailboxPager(pagingSourceFactory: () -> PagingSource<Int, MessageSummary>): Flow<PagingData<Message>> =
        Pager(
            config = PagingConfig(
                pageSize = MAILBOX_PAGE_SIZE,
                initialLoadSize = MAILBOX_PAGE_SIZE * 3,
                enablePlaceholders = false,
                maxSize = MAILBOX_PAGE_SIZE * 5,
            ),
            pagingSourceFactory = pagingSourceFactory,
        ).flow.map { page -> page.map { it.toDomain() } }

    override fun observeFolders(accountId: String): Flow<List<Folder>> =
        folderDao.observeForAccount(accountId).map { rows ->
            rows.map { it.toDomain() }
        }

    override fun observeUnreadCounts(): Flow<List<UnreadCount>> = messageDao.observeUnreadCounts().map { rows ->
        rows.map { it.toDomain() }
    }

    override suspend fun refreshFolders(accountId: String): Result<Unit> = runCatching {
        val account = accountDao.getById(accountId)?.toDomain() ?: error("Account not found")
        val params = connectionFactory.imapParamsFor(account)
        val entities = imapClient.listFolders(params)
            .mapIndexed { index, folder -> folder.toEntity(accountId, index) }
        folderDao.replaceForAccount(accountId, entities)
    }

    override suspend fun getMessage(id: String): Message? = messageDao.getById(id)?.toDomain()

    override suspend fun openMessage(id: String): Result<Message> = withContext(Dispatchers.IO) {
        runCatching {
            // Route on the body-less projection: a cached, already-read message needs no account, no
            // credentials, and no network, so it skips the Keystore decrypt + DataStore read that
            // resolving connection params costs (issue #186). Only the fetch / SEEN-push branches below
            // pull the account and resolve params, and each does so lazily right where it is needed.
            val routing = messageDao.getRouting(id) ?: error("Message not found")
            if (!routing.bodyFetched || !routing.isRead) {
                val account = accountDao.getById(routing.accountId)?.toDomain()
                if (account != null && !routing.bodyFetched) {
                    val params = connectionFactory.imapParamsFor(account)
                    val content = imapClient.fetchBodyMarkingSeen(params, routing.folder, uidOf(id))
                    messageDao.updateBody(id, content.body, content.isHtml, Snippet.of(content.body, content.isHtml))
                    attachmentDao.replaceForMessage(id, content.attachments.map { it.toEntity(id) })
                    messageDao.setRead(id, true)
                } else if (account != null) {
                    // Optimistic, local-only: the reader can render as soon as this returns. The SEEN flag
                    // still needs to reach the server, but that IMAP round trip (connection + STORE) must not
                    // sit on this path (#148/#186) — the body/attachments are already fully local. Pushed on
                    // backgroundScope, which outlives this call.
                    val params = connectionFactory.imapParamsFor(account)
                    messageDao.setRead(id, true)
                    pushSeenFlagInBackground(params, routing.folder, id)
                }
            }
            // The single full-body read, reserved for the value the reader actually renders (issue #186).
            messageDao.getById(id)?.toDomain() ?: error("Message not found")
        }
    }

    /**
     * Best-effort, fire-and-forget propagation of the SEEN flag to the server, off the message-open
     * critical path (#148). Retries a few times with a short backoff, then gives up silently: local state
     * is already correct (the caller set it before launching this), so a permanent failure here just means
     * the server's copy stays "unread" until something else touches the flag — e.g. the message is opened
     * from another client, or a future sync gains upward read-state reconciliation. Today's folder sync
     * does NOT do that: it deliberately leaves cached read/star flags alone when refreshing headers from
     * the server (see `MessageDao.updateHeaderContent`), so it protects an optimistic local flag from
     * being clobbered by stale server state, but it does not re-drive a push that never reached the server
     * either. This retry is in-memory only and does not survive process death mid-backoff.
     */
    private fun pushSeenFlagInBackground(params: ImapConnectionParams, folder: String, id: String) {
        backgroundScope.launch {
            var attempt = 0
            while (true) {
                attempt++
                val result = runCatching { imapClient.setFlag(params, folder, uidOf(id), Flags.Flag.SEEN, true) }
                if (result.isSuccess || attempt >= SEEN_FLAG_PUSH_MAX_ATTEMPTS) return@launch
                delay(SEEN_FLAG_RETRY_BACKOFF_MS * attempt)
            }
        }
    }

    override fun observeAttachments(messageId: String): Flow<List<Attachment>> =
        attachmentDao.observeForMessage(messageId).map { rows ->
            rows.map { it.toDomain() }
        }

    override suspend fun inlineImages(messageId: String): List<InlineImage> = withContext(Dispatchers.IO) {
        // Resolve the message's account/folder once (body-less), then reuse the on-disk cache per cid:
        // image — no per-image message re-read or attachment re-query (the old downloadAttachment N+1, #186).
        val routing = messageDao.getRouting(messageId) ?: return@withContext emptyList()
        val parts = attachmentDao.getForMessage(messageId)
        parts.filter { it.contentId != null }.mapNotNull { row ->
            // Reuse the on-disk attachment cache (download once, then instant + offline). A failed
            // fetch just omits that image, leaving a broken <img> rather than failing the open.
            val file = runCatching {
                ensureAttachmentFile(messageId, routing.accountId, routing.folder, row.partIndex, row.filename)
            }.getOrNull() ?: return@mapNotNull null
            InlineImage(contentId = row.contentId!!, mimeType = row.mimeType, bytes = file.readBytes())
        }
    }

    override suspend fun downloadAttachment(messageId: String, partIndex: Int): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val routing = messageDao.getRouting(messageId) ?: error("Message not found")
                val meta = attachmentDao.getForMessage(messageId).firstOrNull { it.partIndex == partIndex }
                ensureAttachmentFile(
                    messageId,
                    routing.accountId,
                    routing.folder,
                    partIndex,
                    meta?.filename ?: "attachment",
                )
            }
        }

    /**
     * Returns the on-disk file for one attachment part, downloading and caching it on first use so it
     * then opens instantly and offline. Takes the message's already-resolved account/folder so a batch
     * loop (e.g. [inlineImages]) resolves them once instead of re-reading the message row per part (#186).
     * Connection params are resolved lazily — only when the file is missing and must be fetched.
     */
    private suspend fun ensureAttachmentFile(
        messageId: String,
        accountId: String,
        folder: String,
        partIndex: Int,
        filename: String,
    ): File {
        val target = attachmentFile(messageId, partIndex, filename)
        // Reuse a previously downloaded (or pre-fetched) file so it opens instantly and offline.
        if (target.exists() && target.length() > 0L) return target
        val account = accountDao.getById(accountId)?.toDomain() ?: error("Account not found")
        val params = connectionFactory.imapParamsFor(account)
        val downloaded = imapClient.fetchAttachment(params, folder, uidOf(messageId), partIndex)
        target.parentFile?.mkdirs()
        target.outputStream().use { it.write(downloaded.bytes) }
        return target
    }

    override suspend fun downloadedAttachmentParts(messageId: String): Set<Int> = withContext(Dispatchers.IO) {
        attachmentDao.getForMessage(messageId)
            .filter {
                val file = attachmentFile(messageId, it.partIndex, it.filename)
                file.exists() && file.length() > 0L
            }
            .map { it.partIndex }
            .toSet()
    }

    override suspend fun prefetchMessage(messageId: String): Result<Unit> = runCatching {
        val routing = messageDao.getRouting(messageId) ?: return@runCatching
        val account = accountDao.getById(routing.accountId)?.toDomain() ?: return@runCatching
        // Cache the body (peek, so prefetching never marks the message read) and its attachment metadata.
        if (!routing.bodyFetched) {
            val params = connectionFactory.imapParamsFor(account)
            val content = imapClient.fetchBodyPeek(params, routing.folder, uidOf(messageId))
            messageDao.updateBody(messageId, content.body, content.isHtml, Snippet.of(content.body, content.isHtml))
            attachmentDao.replaceForMessage(messageId, content.attachments.map { it.toEntity(messageId) })
        }
        // Auto-download every attachment's bytes into the persistent per-part cache (skips ones present).
        attachmentDao.getForMessage(messageId).forEach { attachment ->
            downloadAttachment(messageId, attachment.partIndex)
        }
    }

    override suspend fun setStarred(id: String, starred: Boolean): Result<Unit> = runCatching {
        messageDao.setStarred(id, starred) // optimistic; next sync reconciles on failure
        val routing = messageDao.getRouting(id)
        val account = routing?.let { accountDao.getById(it.accountId)?.toDomain() }
        if (routing != null && account != null) {
            imapClient.setFlag(
                connectionFactory.imapParamsFor(account),
                routing.folder,
                uidOf(id),
                Flags.Flag.FLAGGED,
                starred,
            )
        }
    }

    override suspend fun deleteMessage(id: String): Result<Unit> = runCatching {
        val routing = messageDao.getRouting(id)
        val account = routing?.let { accountDao.getById(it.accountId)?.toDomain() }
        messageDao.deleteById(id) // optimistic; reappears on next sync if the server delete failed
        if (routing != null && account != null) {
            imapClient.deleteMessage(connectionFactory.imapParamsFor(account), routing.folder, uidOf(id))
        }
    }

    override suspend fun archive(ids: List<String>): Result<Unit> =
        moveByRole(ids, FolderRole.ARCHIVE, fallbackExpunge = false)

    override suspend fun reportSpam(ids: List<String>): Result<Unit> =
        moveByRole(ids, FolderRole.SPAM, fallbackExpunge = false)

    override suspend fun trash(ids: List<String>): Result<Unit> =
        moveByRole(ids, FolderRole.TRASH, fallbackExpunge = true)

    override suspend fun expunge(ids: List<String>): Result<Unit> = runCatching {
        val routings = messageDao.getRoutingByIds(ids)
        messageDao.deleteByIds(ids) // optimistic
        forEachAccountFolder(routings) { params, folder, group ->
            group.forEach { imapClient.deleteMessage(params, folder, uidOf(it.id)) }
        }
    }

    override suspend fun moveToFolder(ids: List<String>, destFolderFullName: String): Result<Unit> = runCatching {
        val routings = messageDao.getRoutingByIds(ids)
        messageDao.deleteByIds(ids) // optimistic
        forEachAccountFolder(routings) { params, folder, group ->
            if (folder != destFolderFullName) {
                imapClient.moveMessages(params, folder, group.map { uidOf(it.id) }, destFolderFullName)
            }
        }
    }

    override suspend fun buildReplyDraft(messageId: String, mode: ReplyMode): Result<String> = runCatching {
        val routing = messageDao.getRouting(messageId) ?: error("Message not found")
        val account = accountDao.getById(routing.accountId)?.toDomain() ?: error("Account not found")
        val params = connectionFactory.imapParamsFor(account)
        val context = imapClient.fetchForReply(params, routing.folder, uidOf(messageId))
        val content = ReplyBuilder.build(context, mode, account.email)
        // Bake the sending account's default signature into the reply/forward body — above the quoted
        // original — so it round-trips as part of the draft (compose won't re-append for drafts). Both
        // the plaintext and HTML forms are stored so the reply can go out as multipart/alternative.
        val settings = accountSettingsRepository.get(routing.accountId)
        val sig = if (settings.signatureEnabled) {
            SignatureBlock.of(signatureRepository.getDefault(routing.accountId))
        } else {
            SignatureBlock.EMPTY
        }
        val draftId = UUID.randomUUID().toString()
        saveDraft(
            Draft(
                id = draftId,
                accountId = routing.accountId,
                to = content.to,
                cc = content.cc,
                subject = content.subject,
                body = sig.plain + content.body,
                updatedAt = System.currentTimeMillis(),
                bodyHtml = sig.html + content.bodyHtml,
                attachments = emptyList(),
            ),
        )
        draftId
    }

    /**
     * Moves messages to each account's folder for [role], resolving the destination per account so the
     * unified inbox works. Removes local rows first (optimistic; reconciled on the next sync). When the
     * role folder is missing: trash falls back to a permanent expunge ([fallbackExpunge]); others fail.
     */
    private suspend fun moveByRole(ids: List<String>, role: FolderRole, fallbackExpunge: Boolean): Result<Unit> =
        runCatching {
            val routings = messageDao.getRoutingByIds(ids)
            messageDao.deleteByIds(ids) // optimistic
            val destByAccount = routings.map { it.accountId }.distinct()
                .associateWith { resolveRoleFolder(it, role) }
            forEachAccountFolder(routings) { params, folder, group ->
                when (val dest = destByAccount[group.first().accountId]) {
                    null ->
                        if (fallbackExpunge) {
                            group.forEach { imapClient.deleteMessage(params, folder, uidOf(it.id)) }
                        } else {
                            error("No ${role.name.lowercase()} folder for this account")
                        }
                    else -> imapClient.moveMessages(params, folder, group.map { uidOf(it.id) }, dest)
                }
            }
        }

    /** Groups [routings] by account then source folder and runs [block] once per (account, folder) group. */
    private suspend fun forEachAccountFolder(
        routings: List<MessageRouting>,
        block: suspend (params: ImapConnectionParams, folder: String, group: List<MessageRouting>) -> Unit,
    ) {
        routings.groupBy { it.accountId }.forEach { (accountId, accountMessages) ->
            val account = accountDao.getById(accountId)?.toDomain() ?: return@forEach
            val params = connectionFactory.imapParamsFor(account)
            accountMessages.groupBy { it.folder }.forEach { (folder, group) -> block(params, folder, group) }
        }
    }

    /**
     * Resolves the full name of an account's folder for [role], refreshing the cache once if needed.
     * Among same-role selectable folders (e.g. `[Gmail]/Spam` via RFC 6154 `\Junk` plus a user label
     * "Spam" matched by name), the server-advertised special-use folder wins regardless of LIST order,
     * so mail reaches the provider's built-in mailbox; absent one, the earliest LISTed folder is kept
     * (`maxByOrNull` returns the first max).
     */
    private suspend fun resolveRoleFolder(accountId: String, role: FolderRole): String? {
        fun pick(folders: List<FolderEntity>) =
            folders.filter { it.role == role.name && it.selectable }.maxByOrNull { it.specialUse }?.fullName
        pick(folderDao.getForAccountOnce(accountId))?.let { return it }
        // The folder cache can be cold (the user may not have opened the drawer yet); refresh and retry.
        runCatching { refreshFolders(accountId) }
        return pick(folderDao.getForAccountOnce(accountId))
    }

    /** Queues the message in the outbox and triggers the send worker; delivery happens in the background. */
    override suspend fun sendMessage(outgoing: OutgoingMessage): Result<Unit> = runCatching {
        requireNotNull(accountDao.getById(outgoing.accountId)) { "Account not found" }
        val outboxId = UUID.randomUUID().toString()
        copyAttachments(outboxId, outgoing.attachments)
        outboxDao.insert(
            OutboxEntity(
                id = outboxId,
                accountId = outgoing.accountId,
                toAddresses = outgoing.to,
                ccAddresses = outgoing.cc,
                bccAddresses = outgoing.bcc,
                subject = outgoing.subject,
                body = outgoing.body,
                createdAt = System.currentTimeMillis(),
                bodyHtml = outgoing.bodyHtml,
                // Persist the cid↔file pairing (in staging-index order) so the send worker can attach
                // an inline image with its Content-ID even though the files are looked up by index.
                attachments = outgoing.attachments.toOutgoingAttachmentsJson(),
            ),
        )
        sendScheduler.sendNow()
    }

    /**
     * Copies the picked attachment URIs into the outbox message's own directory for the worker.
     * Each attachment goes in its own index-named subdirectory so the send worker can restore the
     * original order (a flat listing's order is unspecified), keeping the file's real name intact.
     */
    private fun copyAttachments(outboxId: String, attachments: List<OutgoingAttachment>) {
        if (attachments.isEmpty()) return
        attachments.forEachIndexed { index, attachment ->
            val safeName = sanitizeAttachmentName(attachment.name)
            val dir = File(context.cacheDir, "outbox/$outboxId/$index").apply { mkdirs() }
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(attachment.uri))?.use { input ->
                    File(dir, safeName).outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    override fun observeDrafts(): Flow<List<Draft>> = draftDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun getDraft(id: String): Draft? = draftDao.getById(id)?.toDomain()

    override suspend fun saveDraft(draft: Draft) = draftDao.upsert(draft.toEntity())

    override suspend fun deleteDraft(id: String) {
        // Capture the draft's attachment URIs before the row is gone, then release any persistable grant
        // no other live draft/outbox row still needs (security review): a deleted draft can never reopen.
        val uris = draftDao.getById(id)?.attachments?.toOutgoingAttachments()?.map { it.uri }.orEmpty()
        draftDao.delete(id)
        attachmentUriGrants.releaseUnreferenced(uris)
    }

    override fun observeOutbox(): Flow<List<OutboxMessage>> = outboxDao.observeAll().map { rows ->
        rows.map { it.toDomain() }
    }

    override suspend fun cancelOutboxMessage(id: String) {
        val uris = outboxDao.getById(id)?.attachments?.toOutgoingAttachments()?.map { it.uri }.orEmpty()
        outboxDao.delete(id)
        File(context.cacheDir, "outbox/$id").deleteRecursively()
        // The queued copy is gone; release any picked-URI grant no other live draft/outbox row needs.
        attachmentUriGrants.releaseUnreferenced(uris)
    }

    override suspend fun retryOutbox() = sendScheduler.sendNow()

    override suspend fun searchServer(query: String, accountId: String?, folder: String) {
        accountDao.getAll()
            .filter { accountId == null || it.id == accountId }
            .forEach { entity ->
                val account = entity.toDomain()
                runCatching {
                    val params = connectionFactory.imapParamsFor(account)
                    val results = imapClient.search(params, folder, query, SEARCH_LIMIT)
                    // Mark hits as search-only (inInbox = false) so they show only while searching, and
                    // never overwrite the synced membership of a row that is genuinely in the folder.
                    val entities = results.map { it.toEntity(account.id, folder, inInbox = false) }
                    messageDao.insertNew(entities)
                    entities.forEach {
                        messageDao.updateHeaderContent(
                            id = it.id,
                            sender = it.sender,
                            senderEmail = it.senderEmail,
                            subject = it.subject,
                            timestampMillis = it.timestampMillis,
                            uid = it.uid,
                        )
                    }
                }
            }
    }

    override suspend fun clearSearchResults() = messageDao.deleteSearchRows()

    /**
     * Deterministic cache path for one attachment part, under the FileProvider-shared `attachments/`
     * dir. Keying by message id + part index lets prefetch and on-demand download share the same file
     * and avoids filename collisions between messages.
     */
    private fun attachmentFile(messageId: String, partIndex: Int, filename: String): File {
        val safeName = sanitizeAttachmentName(filename)
        return File(attachmentCacheDir(context.cacheDir, messageId), "$partIndex/$safeName")
    }
}

private const val SEARCH_LIMIT = 50

/** Rows per page for the unified inbox (issue #124) — a page is a few screenfuls of message rows. */
private const val MAILBOX_PAGE_SIZE = 40

/** Attempts for the background best-effort SEEN-flag push before giving up silently (issue #148). */
private const val SEEN_FLAG_PUSH_MAX_ATTEMPTS = 3

/** Base backoff between SEEN-flag push retries, scaled by attempt number (2s, then 4s). */
private const val SEEN_FLAG_RETRY_BACKOFF_MS = 2_000L

/** Message id is "<accountId>:<uid>"; the uid is the trailing segment. */
private fun uidOf(id: String): String = id.substringAfterLast(':')

/**
 * Builds the SQL `LIKE` pattern the paged-search DAO queries take (issue #214), preserving the old
 * `matchesSearch` literal-substring semantics: escape the LIKE metacharacters (`\ % _`) — the `\`
 * first, so the escapes just added aren't themselves re-escaped — then wrap the term in wildcards.
 */
private fun likePattern(query: String): String {
    val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    return "%$escaped%"
}
