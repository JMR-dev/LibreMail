// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.libremail.data.local.dao.AccountDao
import org.libremail.data.local.dao.AttachmentDao
import org.libremail.data.local.dao.MessageDao
import org.libremail.data.local.toDomain
import org.libremail.data.local.toEntity
import org.libremail.data.local.toImapParams
import org.libremail.data.security.CredentialStore
import org.libremail.data.sync.SyncScheduler
import org.libremail.domain.model.Account
import org.libremail.domain.model.ImapConnectionParams
import org.libremail.domain.repository.AccountRepository
import org.libremail.mail.ImapClient

@Singleton
class AccountRepositoryImpl @Inject constructor(
    private val accountDao: AccountDao,
    private val messageDao: MessageDao,
    private val attachmentDao: AttachmentDao,
    private val credentialStore: CredentialStore,
    private val imapClient: ImapClient,
    private val syncScheduler: SyncScheduler,
) : AccountRepository {

    override fun observeAccounts(): Flow<List<Account>> =
        accountDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun testConnection(params: ImapConnectionParams): Result<List<String>> =
        runCatching { imapClient.listFolders(params) }

    override suspend fun addImapAccount(account: Account, password: String): Result<List<String>> = runCatching {
        val folders = imapClient.listFolders(account.toImapParams(secret = password, useXoauth2 = false))
        accountDao.upsert(account.toEntity())
        credentialStore.saveSecret(account.id, password)
        syncScheduler.syncNow()
        folders
    }

    override suspend fun addGmailAccount(
        email: String,
        accessToken: String,
        authStateJson: String,
    ): Result<List<String>> = runCatching {
        val account = Account.gmail(email)
        val folders = imapClient.listFolders(account.toImapParams(secret = accessToken, useXoauth2 = true))
        accountDao.upsert(account.toEntity())
        credentialStore.saveSecret(account.id, authStateJson)
        syncScheduler.syncNow()
        folders
    }

    override suspend fun deleteAccount(id: String) {
        accountDao.deleteById(id)
        credentialStore.delete(id)
        // Remove the account's cached mail so it disappears from the (unified) inbox.
        attachmentDao.deleteByAccountPrefix("$id:%")
        messageDao.deleteByAccount(id)
    }
}
