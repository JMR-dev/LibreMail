// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import javax.inject.Inject
import javax.inject.Singleton
import org.libremail.auth.FreshToken
import org.libremail.auth.GmailAuthManager
import org.libremail.auth.OutlookAuthManager
import org.libremail.data.local.toImapParams
import org.libremail.data.local.toSmtpParams
import org.libremail.data.security.CredentialStore
import org.libremail.domain.model.Account
import org.libremail.domain.model.AuthType
import org.libremail.domain.model.ImapConnectionParams
import org.libremail.domain.model.SmtpParams

/** Resolves an account's stored credential (refreshing the Gmail token when needed) into connection params. */
@Singleton
class MailConnectionFactory @Inject constructor(
    private val credentialStore: CredentialStore,
    private val gmailAuthManager: GmailAuthManager,
    private val outlookAuthManager: OutlookAuthManager,
) {
    suspend fun imapParamsFor(account: Account): ImapConnectionParams =
        account.toImapParams(resolveSecret(account), account.authType != AuthType.PASSWORD_IMAP)

    suspend fun smtpParamsFor(account: Account): SmtpParams =
        account.toSmtpParams(resolveSecret(account), account.authType != AuthType.PASSWORD_IMAP)

    private suspend fun resolveSecret(account: Account): String {
        val stored = credentialStore.loadSecret(account.id)
            ?: error("No stored credentials for ${account.email}")
        return when (account.authType) {
            AuthType.PASSWORD_IMAP -> stored
            AuthType.OAUTH_GMAIL -> refreshedToken(account.id, stored, gmailAuthManager::freshAccessToken)
            AuthType.OAUTH_OUTLOOK -> refreshedToken(account.id, stored, outlookAuthManager::freshAccessToken)
        }
    }

    /** Refreshes an OAuth access token, persisting the updated AuthState when it changes. */
    private suspend fun refreshedToken(
        accountId: String,
        stored: String,
        refresh: suspend (String) -> FreshToken,
    ): String {
        val fresh = refresh(stored)
        if (fresh.authStateJson != stored) credentialStore.saveSecret(accountId, fresh.authStateJson)
        return fresh.accessToken
    }
}
