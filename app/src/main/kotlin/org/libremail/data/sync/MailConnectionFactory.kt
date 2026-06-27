// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import javax.inject.Inject
import javax.inject.Singleton
import org.libremail.auth.GmailAuthManager
import org.libremail.data.local.toImapParams
import org.libremail.data.security.CredentialStore
import org.libremail.domain.model.Account
import org.libremail.domain.model.AuthType
import org.libremail.domain.model.ImapConnectionParams

/** Resolves an account's stored credential (refreshing the Gmail token when needed) into IMAP params. */
@Singleton
class MailConnectionFactory @Inject constructor(
    private val credentialStore: CredentialStore,
    private val gmailAuthManager: GmailAuthManager,
) {
    suspend fun paramsFor(account: Account): ImapConnectionParams {
        val stored = credentialStore.loadSecret(account.id)
            ?: error("No stored credentials for ${account.email}")
        val secret = when (account.authType) {
            AuthType.PASSWORD_IMAP -> stored
            AuthType.OAUTH_GMAIL -> {
                val fresh = gmailAuthManager.freshAccessToken(stored)
                if (fresh.authStateJson != stored) {
                    credentialStore.saveSecret(account.id, fresh.authStateJson)
                }
                fresh.accessToken
            }
        }
        return account.toImapParams(secret, useXoauth2 = account.authType == AuthType.OAUTH_GMAIL)
    }
}
