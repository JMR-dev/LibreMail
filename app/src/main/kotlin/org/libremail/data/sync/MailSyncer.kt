// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.libremail.data.local.dao.AccountDao
import org.libremail.data.local.dao.MessageDao
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
    // Serializes all syncing: syncAll/syncAccount are invoked concurrently by the periodic worker,
    // pull-to-refresh, one-shot syncs, and one IDLE watcher per account. Without this, two runs can
    // both compute the same message as "new" (double-notify) or let a stale deleteInboxNotIn snapshot
    // delete a row another run just inserted.
    private val syncMutex = Mutex()

    /** Syncs every account. Succeeds if at least one account synced (or there are none). */
    suspend fun syncAll(): Result<Int> = syncMutex.withLock {
        val accounts = accountDao.getAll()
        if (accounts.isEmpty()) return@withLock Result.success(0)

        var total = 0
        var firstError: Throwable? = null
        var anySuccess = false
        for (entity in accounts) {
            syncAccountInternal(entity.toDomain()).fold(
                onSuccess = { fetched ->
                    total += fetched
                    anySuccess = true
                },
                onFailure = { error -> if (firstError == null) firstError = error },
            )
        }
        if (anySuccess || firstError == null) Result.success(total) else Result.failure(firstError)
    }

    /** Syncs one account — used by the per-account IDLE watcher so a single push doesn't re-sync all. */
    suspend fun syncAccount(accountId: String): Result<Int> = syncMutex.withLock {
        val entity = accountDao.getById(accountId) ?: return@withLock Result.success(0)
        syncAccountInternal(entity.toDomain())
    }

    private suspend fun syncAccountInternal(account: Account): Result<Int> = runCatching {
        val params = connectionFactory.imapParamsFor(account)
        val fetched = imapClient.fetchRecentInbox(params, INBOX_LIMIT) // cancellable network I/O
        val entities = fetched.map { it.toEntity(account.id) }

        // Persist and notify atomically with respect to cancellation: an IDLE renewal that cancels
        // mid-sync must not drop a notification (the rows would then look "already seen" next time).
        withContext(NonCancellable) {
            val existingIds = messageDao.getInboxIdsForAccount(account.id).toHashSet()
            // Don't notify on the very first sync of an account (would announce the whole inbox).
            val newMessages = if (existingIds.isEmpty()) {
                emptyList()
            } else {
                entities.filter { it.id !in existingIds && !it.isRead }
            }

            if (entities.isEmpty()) {
                messageDao.deleteInboxByAccount(account.id)
            } else {
                val ids = entities.map { it.id }
                messageDao.insertNew(entities)
                // Mark every fetched message as inbox (upgrades any former search-only row) and refresh
                // its display fields — without touching cached bodies or optimistic read/star flags.
                messageDao.markInInbox(ids)
                entities.forEach {
                    messageDao.updateHeaderContent(
                        id = it.id,
                        sender = it.sender,
                        senderEmail = it.senderEmail,
                        subject = it.subject,
                        timestampMillis = it.timestampMillis,
                    )
                }
                messageDao.deleteInboxNotIn(account.id, ids)
            }

            if (newMessages.isNotEmpty() && settingsRepository.isNewMailNotificationsEnabled()) {
                notifier.notifyNewMail(newMessages.sortedByDescending { it.timestampMillis })
            }
        }
        fetched.size
    }

    private companion object {
        const val INBOX_LIMIT = 50
    }
}
