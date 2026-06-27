// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.repository

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import jakarta.mail.Flags
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.libremail.data.local.dao.AccountDao
import org.libremail.data.local.dao.AttachmentDao
import org.libremail.data.local.dao.DraftDao
import org.libremail.data.local.dao.MessageDao
import org.libremail.data.local.dao.OutboxDao
import org.libremail.data.local.entity.OutboxEntity
import org.libremail.data.local.toDomain
import org.libremail.data.local.toEntity
import org.libremail.data.sync.MailConnectionFactory
import org.libremail.data.sync.SendScheduler
import org.libremail.domain.model.Account
import org.libremail.domain.model.Attachment
import org.libremail.domain.model.Draft
import org.libremail.domain.model.Message
import org.libremail.domain.model.OutboxMessage
import org.libremail.domain.model.OutgoingAttachment
import org.libremail.domain.model.OutgoingMessage
import org.libremail.domain.repository.MailRepository
import org.libremail.mail.DownloadedAttachment
import org.libremail.mail.ImapClient

@Singleton
class MailRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messageDao: MessageDao,
    private val accountDao: AccountDao,
    private val attachmentDao: AttachmentDao,
    private val outboxDao: OutboxDao,
    private val draftDao: DraftDao,
    private val imapClient: ImapClient,
    private val connectionFactory: MailConnectionFactory,
    private val sendScheduler: SendScheduler,
) : MailRepository {

    override fun observeMessages(): Flow<List<Message>> =
        messageDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun getMessage(id: String): Message? = messageDao.getById(id)?.toDomain()

    override suspend fun openMessage(id: String): Result<Message> = runCatching {
        val entity = messageDao.getById(id) ?: error("Message not found")
        val account = accountDao.getById(entity.accountId)?.toDomain()
        if (account != null) {
            val params = connectionFactory.imapParamsFor(account)
            if (entity.body.isBlank()) {
                val content = imapClient.fetchBodyMarkingSeen(params, uidOf(id))
                messageDao.updateBody(id, content.body, content.isHtml, snippetOf(content.body))
                attachmentDao.replaceForMessage(id, content.attachments.map { it.toEntity(id) })
                messageDao.setRead(id, true)
            } else if (!entity.isRead) {
                runCatching { imapClient.setFlag(params, uidOf(id), Flags.Flag.SEEN, true) }
                messageDao.setRead(id, true)
            }
        }
        messageDao.getById(id)?.toDomain() ?: error("Message not found")
    }

    override fun observeAttachments(messageId: String): Flow<List<Attachment>> =
        attachmentDao.observeForMessage(messageId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun downloadAttachment(messageId: String, partIndex: Int): Result<File> = runCatching {
        val entity = messageDao.getById(messageId) ?: error("Message not found")
        val account = accountDao.getById(entity.accountId)?.toDomain() ?: error("Account not found")
        val params = connectionFactory.imapParamsFor(account)
        saveToCache(imapClient.fetchAttachment(params, uidOf(messageId), partIndex))
    }

    override suspend fun setStarred(id: String, starred: Boolean): Result<Unit> = runCatching {
        messageDao.setStarred(id, starred) // optimistic; next sync reconciles on failure
        accountFor(id)?.let { account ->
            imapClient.setFlag(connectionFactory.imapParamsFor(account), uidOf(id), Flags.Flag.FLAGGED, starred)
        }
        Unit
    }

    override suspend fun deleteMessage(id: String): Result<Unit> = runCatching {
        val account = accountFor(id)
        messageDao.deleteById(id) // optimistic; reappears on next sync if the server delete failed
        account?.let { imapClient.deleteMessage(connectionFactory.imapParamsFor(it), uidOf(id)) }
        Unit
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
                subject = outgoing.subject,
                body = outgoing.body,
                createdAt = System.currentTimeMillis(),
            ),
        )
        sendScheduler.sendNow()
    }

    /** Copies the picked attachment URIs into the outbox message's own directory for the worker. */
    private fun copyAttachments(outboxId: String, attachments: List<OutgoingAttachment>) {
        if (attachments.isEmpty()) return
        val dir = File(context.cacheDir, "outbox/$outboxId").apply { mkdirs() }
        attachments.forEach { attachment ->
            val safeName = attachment.name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "attachment" }
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(attachment.uri))?.use { input ->
                    File(dir, safeName).outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    override fun observeDrafts(): Flow<List<Draft>> =
        draftDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun getDraft(id: String): Draft? = draftDao.getById(id)?.toDomain()

    override suspend fun saveDraft(draft: Draft) = draftDao.upsert(draft.toEntity())

    override suspend fun deleteDraft(id: String) = draftDao.delete(id)

    override fun observeOutbox(): Flow<List<OutboxMessage>> =
        outboxDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun cancelOutboxMessage(id: String) {
        outboxDao.delete(id)
        File(context.cacheDir, "outbox/$id").deleteRecursively()
    }

    override suspend fun retryOutbox() = sendScheduler.sendNow()

    override suspend fun searchServer(query: String) {
        accountDao.getAll().forEach { entity ->
            val account = entity.toDomain()
            runCatching {
                val results = imapClient.search(connectionFactory.imapParamsFor(account), query, SEARCH_LIMIT)
                val entities = results.map { it.toEntity(account.id) }
                messageDao.insertNew(entities)
                entities.forEach {
                    messageDao.updateHeader(
                        id = it.id,
                        sender = it.sender,
                        senderEmail = it.senderEmail,
                        subject = it.subject,
                        timestampMillis = it.timestampMillis,
                        isRead = it.isRead,
                        isStarred = it.isStarred,
                    )
                }
            }
        }
    }

    /** Writes downloaded bytes to a private cache file that the FileProvider can share. */
    private fun saveToCache(attachment: DownloadedAttachment): File {
        val dir = File(context.cacheDir, "attachments").apply { mkdirs() }
        val safeName = attachment.filename.substringAfterLast('/').substringAfterLast('\\').ifBlank { "attachment" }
        return File(dir, safeName).also { file ->
            file.outputStream().use { it.write(attachment.bytes) }
        }
    }

    private suspend fun accountFor(id: String): Account? {
        val entity = messageDao.getById(id) ?: return null
        return accountDao.getById(entity.accountId)?.toDomain()
    }
}

private const val SEARCH_LIMIT = 50

/** Message id is "<accountId>:<uid>"; the uid is the trailing segment. */
private fun uidOf(id: String): String = id.substringAfterLast(':')

private fun snippetOf(body: String): String =
    body.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim().take(140)
