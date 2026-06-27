// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import javax.inject.Inject
import javax.inject.Singleton
import org.libremail.data.local.dao.AccountDao
import org.libremail.data.local.dao.MessageDao
import org.libremail.data.local.toDomain
import org.libremail.data.local.toEntity
import org.libremail.domain.model.Account
import org.libremail.mail.ImapClient

/** Fetches each account's recent INBOX headers and writes them into Room (the source of truth). */
@Singleton
class MailSyncer @Inject constructor(
    private val accountDao: AccountDao,
    private val messageDao: MessageDao,
    private val imapClient: ImapClient,
    private val connectionFactory: MailConnectionFactory,
) {
    /** Syncs every account. Succeeds if at least one account synced (or there are none). */
    suspend fun syncAll(): Result<Int> {
        val accounts = accountDao.getAll()
        if (accounts.isEmpty()) return Result.success(0)

        var total = 0
        var firstError: Throwable? = null
        var anySuccess = false
        for (entity in accounts) {
            syncAccount(entity.toDomain()).fold(
                onSuccess = { total += it; anySuccess = true },
                onFailure = { error -> if (firstError == null) firstError = error },
            )
        }
        return if (anySuccess || firstError == null) Result.success(total) else Result.failure(firstError!!)
    }

    private suspend fun syncAccount(account: Account): Result<Int> = runCatching {
        val params = connectionFactory.paramsFor(account)
        val fetched = imapClient.fetchRecentInbox(params, INBOX_LIMIT)
        val entities = fetched.map { it.toEntity(account.id) }
        if (entities.isEmpty()) {
            messageDao.deleteByAccount(account.id)
        } else {
            // Insert new headers (keeps any already-cached body), refresh header/flag columns,
            // then drop messages that no longer exist on the server.
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
            messageDao.deleteNotIn(account.id, entities.map { it.id })
        }
        fetched.size
    }

    private companion object {
        const val INBOX_LIMIT = 50
    }
}
