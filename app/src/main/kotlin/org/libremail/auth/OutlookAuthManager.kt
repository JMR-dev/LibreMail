// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import org.json.JSONObject
import org.libremail.BuildConfig

/**
 * Outlook / Microsoft OAuth 2.0 via AppAuth — Authorization Code + PKCE, no client secret.
 *
 * One consent requests the `outlook.office.com` IMAP **and** SMTP scopes. Because both live
 * under a single resource, the resulting access token authenticates IMAP receive and SMTP send
 * over SASL XOAUTH2 — no second token or Graph call is needed. The "common" tenant endpoints
 * accept both personal Microsoft accounts (outlook.com/hotmail) and work/school (Microsoft 365).
 */
@Singleton
class OutlookAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val serviceConfig = AuthorizationServiceConfiguration(
        Uri.parse("https://login.microsoftonline.com/common/oauth2/v2.0/authorize"),
        Uri.parse("https://login.microsoftonline.com/common/oauth2/v2.0/token"),
    )

    /** Outlook is always available: the Microsoft client id ships with the build (it is not a secret). */
    val isConfigured: Boolean get() = BuildConfig.OUTLOOK_OAUTH_CLIENT_ID.isNotBlank()

    fun createAuthIntent(): Intent {
        val request = AuthorizationRequest.Builder(
            serviceConfig,
            BuildConfig.OUTLOOK_OAUTH_CLIENT_ID,
            ResponseTypeValues.CODE,
            Uri.parse(BuildConfig.OUTLOOK_OAUTH_REDIRECT_URI),
        )
            .setScope(
                "openid email offline_access " +
                    "https://outlook.office.com/IMAP.AccessAsUser.All " +
                    "https://outlook.office.com/SMTP.Send",
            )
            .build()
        return AuthorizationService(context).getAuthorizationRequestIntent(request)
    }

    suspend fun exchangeToken(responseIntent: Intent): OAuthResult {
        val response = AuthorizationResponse.fromIntent(responseIntent)
        val exception = AuthorizationException.fromIntent(responseIntent)
        if (response == null) throw exception ?: IllegalStateException("Authorization was cancelled")

        val service = AuthorizationService(context)
        try {
            val tokenResponse = suspendCancellableCoroutine { continuation ->
                service.performTokenRequest(response.createTokenExchangeRequest()) { token, error ->
                    if (token != null) {
                        continuation.resume(token)
                    } else {
                        continuation.resumeWithException(error ?: IllegalStateException("Token exchange failed"))
                    }
                }
            }
            val authState = AuthState(response, exception).apply { update(tokenResponse, null) }
            val email = emailFromIdToken(tokenResponse.idToken)
                ?: throw IllegalStateException("Could not read the account email from the token")
            return OAuthResult(
                email = email,
                accessToken = tokenResponse.accessToken.orEmpty(),
                authStateJson = authState.jsonSerializeString(),
            )
        } finally {
            service.dispose()
        }
    }

    /** Refreshes the access token if needed (using the stored AuthState) for IMAP/SMTP XOAUTH2. */
    suspend fun freshAccessToken(authStateJson: String): FreshToken {
        val authState = AuthState.jsonDeserialize(authStateJson)
        val service = AuthorizationService(context)
        try {
            val accessToken = suspendCancellableCoroutine { continuation ->
                authState.performActionWithFreshTokens(service) { token, _, error ->
                    if (token != null) {
                        continuation.resume(token)
                    } else {
                        continuation.resumeWithException(error ?: IllegalStateException("Token refresh failed"))
                    }
                }
            }
            return FreshToken(accessToken = accessToken, authStateJson = authState.jsonSerializeString())
        } finally {
            service.dispose()
        }
    }

    /** Microsoft id tokens carry the address in `email`, falling back to `preferred_username`. */
    private fun emailFromIdToken(idToken: String?): String? {
        if (idToken.isNullOrBlank()) return null
        return runCatching {
            val payload = idToken.split(".").getOrNull(1) ?: return null
            val json = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
            val claims = JSONObject(json)
            claims.optString("email").ifBlank { claims.optString("preferred_username") }.ifBlank { null }
        }.getOrNull()
    }
}
