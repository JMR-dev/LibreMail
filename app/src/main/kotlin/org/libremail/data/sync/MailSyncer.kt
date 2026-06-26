// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import javax.inject.Inject
import javax.inject.Singleton
import org.libremail.auth.GmailAuthManager
import org.libremail.data.local.dao.AccountDao
import org.libremail.data.local.dao.MessageDao
import org.libremail.data.local.toDomain
import org.libremail.data.local.toEntity
import org.libremail.data.local.toImapParams
import org.libremail.data.security.CredentialStore
import org.libremail.domain.model.Account
import org.libremail.domain.model.AuthType
import org.libremail.mail.ImapClient

/** Fetches each account's recent INBOX headers and writes them into Room (the source of truth). */
@Singleton
class MailSyncer @Inject constructor(
    private val accountDao: AccountDao,
    private val messageDao: MessageDao,
    private val credentialStore: CredentialStore,
    private val imapClient: ImapClient,
    private val gmailAuthManager: GmailAuthManager,
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
        val secret = resolveSecret(account)
        val fetched = imapClient.fetchRecentInbox(
            account.toImapParams(secret = secret, useXoauth2 = account.authType == AuthType.OAUTH_GMAIL),
            INBOX_LIMIT,
        )
        messageDao.replaceAccountMessages(account.id, fetched.map { it.toEntity(account.id) })
        fetched.size
    }

    /** Returns a usable IMAP secret, refreshing and re-persisting the OAuth token when needed. */
    private suspend fun resolveSecret(account: Account): String {
        val stored = credentialStore.loadSecret(account.id)
            ?: error("No stored credentials for ${account.email}")
        return when (account.authType) {
            AuthType.PASSWORD_IMAP -> stored
            AuthType.OAUTH_GMAIL -> {
                val fresh = gmailAuthManager.freshAccessToken(stored)
                if (fresh.authStateJson != stored) {
                    credentialStore.saveSecret(account.id, fresh.authStateJson)
                }
                fresh.accessToken
            }
        }
    }

    private companion object {
        const val INBOX_LIMIT = 50
    }
}
