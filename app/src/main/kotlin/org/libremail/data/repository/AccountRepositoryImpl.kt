// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.libremail.data.local.dao.AccountDao
import org.libremail.data.local.entity.AccountEntity
import org.libremail.data.local.entity.ServerConfigEmbedded
import org.libremail.data.security.CredentialStore
import org.libremail.domain.model.Account
import org.libremail.domain.model.AuthType
import org.libremail.domain.model.ImapConnectionParams
import org.libremail.domain.model.MailSecurity
import org.libremail.domain.model.ServerConfig
import org.libremail.domain.repository.AccountRepository
import org.libremail.mail.ImapClient

@Singleton
class AccountRepositoryImpl @Inject constructor(
    private val accountDao: AccountDao,
    private val credentialStore: CredentialStore,
    private val imapClient: ImapClient,
) : AccountRepository {

    override fun observeAccounts(): Flow<List<Account>> =
        accountDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun testConnection(params: ImapConnectionParams): Result<List<String>> =
        runCatching { imapClient.listFolders(params) }

    override suspend fun addImapAccount(account: Account, password: String): Result<List<String>> = runCatching {
        val folders = imapClient.listFolders(account.imapParams(secret = password, useXoauth2 = false))
        accountDao.upsert(account.toEntity())
        credentialStore.saveSecret(account.id, password)
        folders
    }

    override suspend fun addGmailAccount(
        email: String,
        accessToken: String,
        authStateJson: String,
    ): Result<List<String>> = runCatching {
        val account = Account.gmail(email)
        val folders = imapClient.listFolders(account.imapParams(secret = accessToken, useXoauth2 = true))
        accountDao.upsert(account.toEntity())
        credentialStore.saveSecret(account.id, authStateJson)
        folders
    }

    override suspend fun deleteAccount(id: String) {
        accountDao.deleteById(id)
        credentialStore.delete(id)
    }
}

private fun Account.imapParams(secret: String, useXoauth2: Boolean) = ImapConnectionParams(
    host = imap.host,
    port = imap.port,
    security = imap.security,
    username = email,
    secret = secret,
    useXoauth2 = useXoauth2,
)

private fun Account.toEntity() = AccountEntity(
    id = id,
    email = email,
    displayName = displayName,
    authType = authType.name,
    imap = ServerConfigEmbedded(imap.host, imap.port, imap.security.name),
    smtp = ServerConfigEmbedded(smtp.host, smtp.port, smtp.security.name),
)

private fun AccountEntity.toDomain() = Account(
    id = id,
    email = email,
    displayName = displayName,
    authType = runCatching { AuthType.valueOf(authType) }.getOrDefault(AuthType.PASSWORD_IMAP),
    imap = ServerConfig(imap.host, imap.port, imap.security.toSecurity()),
    smtp = ServerConfig(smtp.host, smtp.port, smtp.security.toSecurity()),
)

private fun String.toSecurity() =
    runCatching { MailSecurity.valueOf(this) }.getOrDefault(MailSecurity.SSL_TLS)
