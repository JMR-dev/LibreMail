// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import javax.inject.Inject
import javax.inject.Singleton
import org.libremail.data.local.dao.AccountDao
import org.libremail.data.local.dao.MessageDao
import org.libremail.data.local.entity.MessageEntity
import org.libremail.data.local.toDomain
import org.libremail.data.local.toEntity
import org.libremail.data.settings.SettingsRepository
import org.libremail.domain.model.Account
import org.libremail.mail.ImapClient
import org.libremail.notifications.MailNotifier

/** Fetches each account's recent INBOX headers into Room and notifies about newly-arrived mail. */
@Singleton
class MailSyncer @Inject constructor(
    private val accountDao: AccountDao,
    private val messageDao: MessageDao,
    private val imapClient: ImapClient,
    private val connectionFactory: MailConnectionFactory,
    private val settingsRepository: SettingsRepository,
    private val notifier: MailNotifier,
) {
    /** Syncs every account. Succeeds if at least one account synced (or there are none). */
    suspend fun syncAll(): Result<Int> {
        val accounts = accountDao.getAll()
        if (accounts.isEmpty()) return Result.success(0)

        var total = 0
        var firstError: Throwable? = null
        var anySuccess = false
        val newMessages = mutableListOf<MessageEntity>()
        for (entity in accounts) {
            syncAccount(entity.toDomain()).fold(
                onSuccess = { result ->
                    total += result.fetched
                    newMessages += result.newMessages
                    anySuccess = true
                },
                onFailure = { error -> if (firstError == null) firstError = error },
            )
        }

        if (newMessages.isNotEmpty() && settingsRepository.isNewMailNotificationsEnabled()) {
            notifier.notifyNewMail(newMessages.sortedByDescending { it.timestampMillis })
        }
        return if (anySuccess || firstError == null) Result.success(total) else Result.failure(firstError!!)
    }

    private suspend fun syncAccount(account: Account): Result<AccountSyncResult> = runCatching {
        val params = connectionFactory.imapParamsFor(account)
        val fetched = imapClient.fetchRecentInbox(params, INBOX_LIMIT)
        val entities = fetched.map { it.toEntity(account.id) }

        val existingIds = messageDao.getIdsForAccount(account.id).toHashSet()
        // Don't notify on the very first sync of an account (would announce the whole inbox).
        val newMessages = if (existingIds.isEmpty()) {
            emptyList()
        } else {
            entities.filter { it.id !in existingIds && !it.isRead }
        }

        if (entities.isEmpty()) {
            messageDao.deleteByAccount(account.id)
        } else {
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
        AccountSyncResult(fetched = fetched.size, newMessages = newMessages)
    }

    private data class AccountSyncResult(val fetched: Int, val newMessages: List<MessageEntity>)

    private companion object {
        const val INBOX_LIMIT = 50
    }
}
