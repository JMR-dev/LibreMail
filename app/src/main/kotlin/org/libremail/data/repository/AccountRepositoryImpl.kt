// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.libremail.data.local.dao.AccountDao
import org.libremail.data.local.dao.BackfillProgressDao
import org.libremail.data.local.dao.FolderDao
import org.libremail.data.local.dao.MessageDao
import org.libremail.data.local.toDomain
import org.libremail.data.local.toEntity
import org.libremail.data.local.toImapParams
import org.libremail.data.security.CredentialStore
import org.libremail.data.settings.AccountSettingsRepository
import org.libremail.data.sync.SyncScheduler
import org.libremail.domain.model.Account
import org.libremail.domain.model.ImapConnectionParams
import org.libremail.domain.repository.AccountRepository
import org.libremail.mail.ImapClient
import org.libremail.notifications.MailNotifier
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountRepositoryImpl @Inject constructor(
    private val accountDao: AccountDao,
    private val messageDao: MessageDao,
    private val folderDao: FolderDao,
    private val backfillProgressDao: BackfillProgressDao,
    private val credentialStore: CredentialStore,
    private val imapClient: ImapClient,
    private val syncScheduler: SyncScheduler,
    private val accountSettingsRepository: AccountSettingsRepository,
    private val mailNotifier: MailNotifier,
) : AccountRepository {

    override fun observeAccounts(): Flow<List<Account>> = accountDao.observeAll().map { rows ->
        rows.map { it.toDomain() }
    }

    override suspend fun testConnection(params: ImapConnectionParams): Result<List<String>> = runCatching {
        imapClient.listFolders(params).map { it.fullName }
    }

    override suspend fun addImapAccount(account: Account, password: String): Result<List<String>> = runCatching {
        val folders = imapClient.listFolders(account.toImapParams(secret = password, useXoauth2 = false))
        accountDao.upsert(account.toEntity())
        accountSettingsRepository.ensureDefaults(account.id)
        credentialStore.saveSecret(account.id, password)
        mailNotifier.ensureAccountChannel(account)
        syncScheduler.syncNow()
        syncScheduler.backfillNow() // start caching this account's full history in the background (#12)
        folders.map { it.fullName }
    }

    override suspend fun addOutlookAccount(
        email: String,
        accessToken: String,
        authStateJson: String,
    ): Result<List<String>> = runCatching {
        val account = Account.outlook(email)
        val folders = imapClient.listFolders(account.toImapParams(secret = accessToken, useXoauth2 = true))
        accountDao.upsert(account.toEntity())
        accountSettingsRepository.ensureDefaults(account.id)
        credentialStore.saveSecret(account.id, authStateJson)
        mailNotifier.ensureAccountChannel(account)
        syncScheduler.syncNow()
        syncScheduler.backfillNow() // start caching this account's full history in the background (#12)
        folders.map { it.fullName }
    }

    override suspend fun deleteAccount(id: String) {
        accountDao.deleteById(id)
        credentialStore.delete(id)
        mailNotifier.deleteAccountChannel(id)
        // Remove the account's cached mail (attachment rows cascade via the foreign key), folders, and
        // backfill progress. The account_settings row is removed automatically by its cascading FK.
        messageDao.deleteByAccount(id)
        folderDao.deleteForAccount(id)
        backfillProgressDao.deleteForAccount(id)
    }
}
