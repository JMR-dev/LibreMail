// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.repository

import android.content.Context
import android.net.Uri
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import dagger.hilt.android.qualifiers.ApplicationContext
import jakarta.mail.Flags
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.libremail.data.ReplyBuilder
import org.libremail.data.SignatureBlock
import org.libremail.data.Snippet
import org.libremail.data.attachmentCacheDir
import org.libremail.data.local.dao.AccountDao
import org.libremail.data.local.dao.AttachmentDao
import org.libremail.data.local.dao.DraftDao
import org.libremail.data.local.dao.FolderDao
import org.libremail.data.local.dao.MessageDao
import org.libremail.data.local.dao.OutboxDao
import org.libremail.data.local.entity.FolderEntity
import org.libremail.data.local.entity.MessageEntity
import org.libremail.data.local.entity.OutboxEntity
import org.libremail.data.local.toDomain
import org.libremail.data.local.toEntity
import org.libremail.data.settings.AccountSettingsRepository
import org.libremail.data.settings.SignatureRepository
import org.libremail.data.sync.MailConnectionFactory
import org.libremail.data.sync.SendScheduler
import org.libremail.domain.model.Attachment
import org.libremail.domain.model.Draft
import org.libremail.domain.model.Folder
import org.libremail.domain.model.FolderRole
import org.libremail.domain.model.ImapConnectionParams
import org.libremail.domain.model.Message
import org.libremail.domain.model.OutboxMessage
import org.libremail.domain.model.OutgoingAttachment
import org.libremail.domain.model.OutgoingMessage
import org.libremail.domain.model.ReplyMode
import org.libremail.domain.model.UnreadCount
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
) : MailRepository {

    override fun observeFolderMessages(accountId: String, folder: String): Flow<List<Message>> =
        messageDao.observeFolderSummaries(accountId, folder).map { rows -> rows.map { it.toDomain() } }

    override fun observeUnifiedFolderMessages(folder: String): Flow<List<Message>> =
        messageDao.observeUnifiedFolderSummaries(folder).map { rows -> rows.map { it.toDomain() } }

    override fun pagedUnifiedFolderMessages(folder: String): Flow<PagingData<Message>> = Pager(
        config = PagingConfig(
            // A page comfortably exceeds a screenful so scrolling rarely waits on a load; loading
            // three pages up front fills the first viewport without a visible gap. Placeholders
            // are off: the row height varies (snippet/account label), so a fixed-height placeholder
            // would jump, and the list never needs a scrollbar sized to the full (uncounted) inbox.
            pageSize = MAILBOX_PAGE_SIZE,
            initialLoadSize = MAILBOX_PAGE_SIZE * 3,
            enablePlaceholders = false,
        ),
        pagingSourceFactory = { messageDao.pagingUnifiedFolderSummaries(folder) },
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

    override suspend fun openMessage(id: String): Result<Message> = runCatching {
        val entity = messageDao.getById(id) ?: error("Message not found")
        val account = accountDao.getById(entity.accountId)?.toDomain()
        if (account != null) {
            val params = connectionFactory.imapParamsFor(account)
            if (!entity.bodyFetched) {
                val content = imapClient.fetchBodyMarkingSeen(params, entity.folder, uidOf(id))
                messageDao.updateBody(id, content.body, content.isHtml, Snippet.of(content.body, content.isHtml))
                attachmentDao.replaceForMessage(id, content.attachments.map { it.toEntity(id) })
                messageDao.setRead(id, true)
            } else if (!entity.isRead) {
                runCatching { imapClient.setFlag(params, entity.folder, uidOf(id), Flags.Flag.SEEN, true) }
                messageDao.setRead(id, true)
            }
        }
        messageDao.getById(id)?.toDomain() ?: error("Message not found")
    }

    override fun observeAttachments(messageId: String): Flow<List<Attachment>> =
        attachmentDao.observeForMessage(messageId).map { rows ->
            rows.map { it.toDomain() }
        }

    override suspend fun downloadAttachment(messageId: String, partIndex: Int): Result<File> = runCatching {
        val entity = messageDao.getById(messageId) ?: error("Message not found")
        val meta = attachmentDao.getForMessage(messageId).firstOrNull { it.partIndex == partIndex }
        val target = attachmentFile(messageId, partIndex, meta?.filename ?: "attachment")
        // Reuse a previously downloaded (or pre-fetched) file so it opens instantly and offline.
        if (target.exists() && target.length() > 0L) return@runCatching target
        val account = accountDao.getById(entity.accountId)?.toDomain() ?: error("Account not found")
        val params = connectionFactory.imapParamsFor(account)
        val downloaded = imapClient.fetchAttachment(params, entity.folder, uidOf(messageId), partIndex)
        target.parentFile?.mkdirs()
        target.outputStream().use { it.write(downloaded.bytes) }
        target
    }

    override suspend fun downloadedAttachmentParts(messageId: String): Set<Int> = attachmentDao.getForMessage(messageId)
        .filter {
            val file = attachmentFile(messageId, it.partIndex, it.filename)
            file.exists() && file.length() > 0L
        }
        .map { it.partIndex }
        .toSet()

    override suspend fun prefetchMessage(messageId: String): Result<Unit> = runCatching {
        val entity = messageDao.getById(messageId) ?: return@runCatching
        val account = accountDao.getById(entity.accountId)?.toDomain() ?: return@runCatching
        val params = connectionFactory.imapParamsFor(account)
        // Cache the body (peek, so prefetching never marks the message read) and its attachment metadata.
        if (!entity.bodyFetched) {
            val content = imapClient.fetchBodyPeek(params, entity.folder, uidOf(messageId))
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
        val entity = messageDao.getById(id)
        val account = entity?.let { accountDao.getById(it.accountId)?.toDomain() }
        if (entity != null && account != null) {
            imapClient.setFlag(
                connectionFactory.imapParamsFor(account),
                entity.folder,
                uidOf(id),
                Flags.Flag.FLAGGED,
                starred,
            )
        }
    }

    override suspend fun deleteMessage(id: String): Result<Unit> = runCatching {
        val entity = messageDao.getById(id)
        val account = entity?.let { accountDao.getById(it.accountId)?.toDomain() }
        messageDao.deleteById(id) // optimistic; reappears on next sync if the server delete failed
        if (entity != null && account != null) {
            imapClient.deleteMessage(connectionFactory.imapParamsFor(account), entity.folder, uidOf(id))
        }
    }

    override suspend fun archive(ids: List<String>): Result<Unit> =
        moveByRole(ids, FolderRole.ARCHIVE, fallbackExpunge = false)

    override suspend fun reportSpam(ids: List<String>): Result<Unit> =
        moveByRole(ids, FolderRole.SPAM, fallbackExpunge = false)

    override suspend fun trash(ids: List<String>): Result<Unit> =
        moveByRole(ids, FolderRole.TRASH, fallbackExpunge = true)

    override suspend fun expunge(ids: List<String>): Result<Unit> = runCatching {
        val entities = ids.mapNotNull { messageDao.getById(it) }
        messageDao.deleteByIds(ids) // optimistic
        forEachAccountFolder(entities) { params, folder, group ->
            group.forEach { imapClient.deleteMessage(params, folder, uidOf(it.id)) }
        }
    }

    override suspend fun moveToFolder(ids: List<String>, destFolderFullName: String): Result<Unit> = runCatching {
        val entities = ids.mapNotNull { messageDao.getById(it) }
        messageDao.deleteByIds(ids) // optimistic
        forEachAccountFolder(entities) { params, folder, group ->
            if (folder != destFolderFullName) {
                imapClient.moveMessages(params, folder, group.map { uidOf(it.id) }, destFolderFullName)
            }
        }
    }

    override suspend fun buildReplyDraft(messageId: String, mode: ReplyMode): Result<String> = runCatching {
        val entity = messageDao.getById(messageId) ?: error("Message not found")
        val account = accountDao.getById(entity.accountId)?.toDomain() ?: error("Account not found")
        val params = connectionFactory.imapParamsFor(account)
        val context = imapClient.fetchForReply(params, entity.folder, uidOf(messageId))
        val content = ReplyBuilder.build(context, mode, account.email)
        // Bake the sending account's default signature into the reply/forward body — above the quoted
        // original — so it round-trips as part of the draft (compose won't re-append for drafts). Both
        // the plaintext and HTML forms are stored so the reply can go out as multipart/alternative.
        val settings = accountSettingsRepository.get(entity.accountId)
        val sig = if (settings.signatureEnabled) {
            SignatureBlock.of(signatureRepository.getDefault(entity.accountId))
        } else {
            SignatureBlock.EMPTY
        }
        val draftId = UUID.randomUUID().toString()
        saveDraft(
            Draft(
                id = draftId,
                accountId = entity.accountId,
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
            val entities = ids.mapNotNull { messageDao.getById(it) }
            messageDao.deleteByIds(ids) // optimistic
            val destByAccount = entities.map { it.accountId }.distinct()
                .associateWith { resolveRoleFolder(it, role) }
            forEachAccountFolder(entities) { params, folder, group ->
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

    /** Groups [entities] by account then source folder and runs [block] once per (account, folder) group. */
    private suspend fun forEachAccountFolder(
        entities: List<MessageEntity>,
        block: suspend (params: ImapConnectionParams, folder: String, group: List<MessageEntity>) -> Unit,
    ) {
        entities.groupBy { it.accountId }.forEach { (accountId, accountMessages) ->
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
            val safeName = attachment.name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "attachment" }
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

    override suspend fun deleteDraft(id: String) = draftDao.delete(id)

    override fun observeOutbox(): Flow<List<OutboxMessage>> = outboxDao.observeAll().map { rows ->
        rows.map { it.toDomain() }
    }

    override suspend fun cancelOutboxMessage(id: String) {
        outboxDao.delete(id)
        File(context.cacheDir, "outbox/$id").deleteRecursively()
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
        val safeName = filename.substringAfterLast('/').substringAfterLast('\\').ifBlank { "attachment" }
        return File(attachmentCacheDir(context.cacheDir, messageId), "$partIndex/$safeName")
    }
}

private const val SEARCH_LIMIT = 50

/** Rows per page for the unified inbox (issue #124) — a page is a few screenfuls of message rows. */
private const val MAILBOX_PAGE_SIZE = 40

/** Message id is "<accountId>:<uid>"; the uid is the trailing segment. */
private fun uidOf(id: String): String = id.substringAfterLast(':')
