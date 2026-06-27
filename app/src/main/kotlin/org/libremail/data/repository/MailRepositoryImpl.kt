// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.repository

import jakarta.mail.Flags
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.libremail.data.local.dao.AccountDao
import org.libremail.data.local.dao.MessageDao
import org.libremail.data.local.toDomain
import org.libremail.data.sync.MailConnectionFactory
import org.libremail.domain.model.Account
import org.libremail.domain.model.Message
import org.libremail.domain.model.OutgoingMessage
import org.libremail.domain.repository.MailRepository
import org.libremail.mail.ImapClient
import org.libremail.mail.SmtpSender

@Singleton
class MailRepositoryImpl @Inject constructor(
    private val messageDao: MessageDao,
    private val accountDao: AccountDao,
    private val imapClient: ImapClient,
    private val smtpSender: SmtpSender,
    private val connectionFactory: MailConnectionFactory,
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
                messageDao.setRead(id, true)
            } else if (!entity.isRead) {
                runCatching { imapClient.setFlag(params, uidOf(id), Flags.Flag.SEEN, true) }
                messageDao.setRead(id, true)
            }
        }
        messageDao.getById(id)?.toDomain() ?: error("Message not found")
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

    override suspend fun sendMessage(outgoing: OutgoingMessage): Result<Unit> = runCatching {
        val account = accountDao.getById(outgoing.accountId)?.toDomain() ?: error("Account not found")
        smtpSender.send(connectionFactory.smtpParamsFor(account), from = account.email, message = outgoing)
    }

    private suspend fun accountFor(id: String): Account? {
        val entity = messageDao.getById(id) ?: return null
        return accountDao.getById(entity.accountId)?.toDomain()
    }
}

/** Message id is "<accountId>:<uid>"; the uid is the trailing segment. */
private fun uidOf(id: String): String = id.substringAfterLast(':')

private fun snippetOf(body: String): String =
    body.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim().take(140)
