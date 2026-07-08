// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.libremail.auth.FreshToken
import org.libremail.auth.OutlookAuthManager
import org.libremail.data.local.toImapParams
import org.libremail.data.local.toSmtpParams
import org.libremail.data.security.CredentialStore
import org.libremail.data.settings.SettingsRepository
import org.libremail.domain.model.Account
import org.libremail.domain.model.AuthType
import org.libremail.domain.model.ImapConnectionParams
import org.libremail.domain.model.SmtpParams
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Resolves an account's stored credential (refreshing OAuth tokens when needed) into connection params. */
@Singleton
class MailConnectionFactory @Inject constructor(
    private val credentialStore: CredentialStore,
    private val outlookAuthManager: OutlookAuthManager,
    private val settingsRepository: SettingsRepository,
) {
    private data class CachedToken(val token: String, val expiry: Long?)

    /** Per-account lock so concurrent refreshes can't redeem the same (rotating) refresh token twice. */
    private val refreshMutexes = ConcurrentHashMap<String, Mutex>()

    /** In-memory access-token cache keyed by "accountId|scope", to avoid redeeming a still-valid token. */
    private val tokenCache = ConcurrentHashMap<String, CachedToken>()

    suspend fun imapParamsFor(account: Account): ImapConnectionParams = account.toImapParams(
        resolveSecret(account),
        account.authType != AuthType.PASSWORD_IMAP,
        strictStartTls(),
    )

    suspend fun smtpParamsFor(account: Account): SmtpParams = account.toSmtpParams(
        resolveSecret(account),
        account.authType != AuthType.PASSWORD_IMAP,
        strictStartTls(),
    )

    /** A fresh Microsoft Graph access token for the primary Outlook (sendMail) send path. */
    suspend fun graphTokenFor(account: Account): String =
        cachedAccessToken(account.id, SCOPE_GRAPH, outlookAuthManager::freshGraphToken)

    private suspend fun resolveSecret(account: Account): String = when (account.authType) {
        AuthType.PASSWORD_IMAP ->
            credentialStore.loadSecret(account.id) ?: throw MissingCredentialsException()
        AuthType.OAUTH_OUTLOOK ->
            cachedAccessToken(account.id, SCOPE_OUTLOOK, outlookAuthManager::freshOutlookToken)
    }

    /**
     * Returns a cached access token while it is still valid, otherwise refreshes under the account's
     * lock (re-checking the cache first, so a concurrent caller redeems only once) and persists the
     * updated AuthState.
     */
    private suspend fun cachedAccessToken(
        accountId: String,
        scope: String,
        refresh: suspend (String) -> FreshToken,
    ): String {
        validCachedToken(accountId, scope)?.let { return it }
        // computeIfAbsent (not getOrPut) so concurrent first-callers share one mutex per account.
        return refreshMutexes.computeIfAbsent(accountId) { Mutex() }.withLock {
            validCachedToken(accountId, scope)?.let { return@withLock it }
            val stored = credentialStore.loadSecret(accountId) ?: throw MissingCredentialsException()
            val fresh = refresh(stored)
            if (fresh.authStateJson != stored) credentialStore.saveSecret(accountId, fresh.authStateJson)
            tokenCache["$accountId|$scope"] = CachedToken(fresh.accessToken, fresh.accessTokenExpiry)
            fresh.accessToken
        }
    }

    private fun validCachedToken(accountId: String, scope: String): String? {
        val cached = tokenCache["$accountId|$scope"] ?: return null
        val expiry = cached.expiry ?: return null // unknown expiry — don't trust the cache
        return cached.token.takeIf { expiry - EXPIRY_BUFFER_MS > System.currentTimeMillis() }
    }

    private suspend fun strictStartTls(): Boolean = !settingsRepository.settings.first().allowStartTls

    private companion object {
        const val SCOPE_OUTLOOK = "outlook"
        const val SCOPE_GRAPH = "graph"
        const val EXPIRY_BUFFER_MS = 60_000L
    }
}
