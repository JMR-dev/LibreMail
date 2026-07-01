// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.libremail.data.local.dao.AccountDao
import org.libremail.data.local.dao.MessageDao
import org.libremail.data.local.toDomain
import org.libremail.data.local.toEntity
import org.libremail.data.settings.AccountSettingsRepository
import org.libremail.data.settings.FetchPolicy
import org.libremail.data.settings.SettingsRepository
import org.libremail.domain.model.Account
import org.libremail.domain.repository.MailRepository
import org.libremail.mail.ImapClient
import org.libremail.notifications.MailNotifier

/** Fetches account folders' headers into Room and notifies about newly-arrived inbox mail. */
@Singleton
class MailSyncer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountDao: AccountDao,
    private val messageDao: MessageDao,
    private val imapClient: ImapClient,
    private val connectionFactory: MailConnectionFactory,
    private val settingsRepository: SettingsRepository,
    private val accountSettingsRepository: AccountSettingsRepository,
    private val notifier: MailNotifier,
    private val mailRepository: MailRepository,
) : Syncer {
    // Serializes all syncing: syncAll/syncAccount/syncFolder are invoked concurrently by the periodic
    // worker, pull-to-refresh, one-shot syncs, folder opens, and one IDLE watcher per account. Without
    // this, two runs can both compute the same message as "new" (double-notify) or let a stale
    // deleteSyncedNotIn snapshot delete a row another run just inserted.
    private val syncMutex = Mutex()

    /** Syncs every account's inbox. Succeeds if at least one account synced (or there are none). */
    override suspend fun syncAll(): Result<Int> {
        val accounts = accountDao.getAll().map { it.toDomain() }
        if (accounts.isEmpty()) return Result.success(0)

        val result = syncMutex.withLock {
            var total = 0
            var firstError: Throwable? = null
            var anySuccess = false
            for (account in accounts) {
                syncFolderHeaders(account, INBOX, notify = true).fold(
                    onSuccess = { fetched ->
                        total += fetched
                        anySuccess = true
                    },
                    onFailure = { error -> if (firstError == null) firstError = error },
                )
            }
            if (anySuccess || firstError == null) Result.success(total) else Result.failure(firstError)
        }
        if (result.isSuccess) accounts.forEach { prefetchIfEnabled(it, INBOX) }
        return result
    }

    /** Syncs one account's inbox — used by the per-account IDLE watcher so a push doesn't re-sync all. */
    override suspend fun syncAccount(accountId: String): Result<Int> {
        val account = accountDao.getById(accountId)?.toDomain() ?: return Result.success(0)
        val result = syncMutex.withLock { syncFolderHeaders(account, INBOX, notify = true) }
        if (result.isSuccess) prefetchIfEnabled(account, INBOX)
        return result
    }

    /** Syncs one (account, folder) on demand — used when a folder is opened or refreshed (no notify). */
    override suspend fun syncFolder(accountId: String, folder: String): Result<Int> {
        val account = accountDao.getById(accountId)?.toDomain() ?: return Result.success(0)
        val result = syncMutex.withLock { syncFolderHeaders(account, folder, notify = false) }
        if (result.isSuccess) prefetchIfEnabled(account, folder)
        return result
    }

    private suspend fun syncFolderHeaders(account: Account, folder: String, notify: Boolean): Result<Int> =
        runCatching {
            val params = connectionFactory.imapParamsFor(account)
            val fetched = imapClient.fetchRecent(params, folder, FETCH_LIMIT) // cancellable network I/O
            val entities = fetched.map { it.toEntity(account.id, folder) }

            // Persist and notify atomically with respect to cancellation: an IDLE renewal that cancels
            // mid-sync must not drop a notification (the rows would then look "already seen" next time).
            withContext(NonCancellable) {
                val existingIds = messageDao.getSyncedIds(account.id, folder).toHashSet()
                // Don't notify on the very first sync of a folder (would announce everything in it).
                val newMessages = if (existingIds.isEmpty()) {
                    emptyList()
                } else {
                    entities.filter { it.id !in existingIds && !it.isRead }
                }

                if (entities.isEmpty()) {
                    messageDao.deleteSyncedByAccountFolder(account.id, folder)
                } else {
                    val ids = entities.map { it.id }
                    messageDao.insertNew(entities)
                    // Mark every fetched message as synced (upgrades any former search-only row) and refresh
                    // its display fields — without touching cached bodies or optimistic read/star flags.
                    messageDao.markSynced(ids)
                    entities.forEach {
                        messageDao.updateHeaderContent(
                            id = it.id,
                            sender = it.sender,
                            senderEmail = it.senderEmail,
                            subject = it.subject,
                            timestampMillis = it.timestampMillis,
                        )
                    }
                    messageDao.deleteSyncedNotIn(account.id, folder, ids)
                }

                if (notify && newMessages.isNotEmpty() &&
                    settingsRepository.isNewMailNotificationsEnabled() &&
                    accountSettingsRepository.get(account.id).notificationsEnabled
                ) {
                    notifier.notifyNewMail(account, newMessages.sortedByDescending { it.timestampMillis })
                }
            }
            fetched.size
        }

    /**
     * Aggressively pre-caches each not-yet-fetched message's full content (body + attachments) per the
     * user's [FetchPolicy]. Runs outside [syncMutex] so these downloads don't block pull-to-refresh or
     * other syncs, and is cancellable between messages so an IDLE renewal stops it promptly.
     */
    private suspend fun prefetchIfEnabled(account: Account, folder: String) {
        val shouldPrefetch = when (settingsRepository.fetchPolicy()) {
            FetchPolicy.ALWAYS -> true
            FetchPolicy.WIFI_ONLY -> isUnmetered()
            FetchPolicy.ON_DEMAND -> false
        }
        if (!shouldPrefetch) return
        for (id in messageDao.getUnfetchedIds(account.id, folder)) {
            currentCoroutineContext().ensureActive()
            mailRepository.prefetchMessage(id) // best-effort; swallows its own per-message failures
        }
    }

    /** True when the active network is unmetered (e.g. Wi-Fi), used by [FetchPolicy.WIFI_ONLY]. */
    private fun isUnmetered(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private companion object {
        const val INBOX = "INBOX"
        const val FETCH_LIMIT = 50
    }
}
