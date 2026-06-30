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
import net.openid.appauth.GrantTypeValues
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest
import org.json.JSONObject
import org.libremail.BuildConfig

/**
 * Outlook / Microsoft OAuth 2.0 via AppAuth — Authorization Code + PKCE, no client secret.
 *
 * Send goes through Microsoft **Graph** (`sendMail`, their first-class/preferred API); IMAP
 * receive — and SMTP send as a fallback — go through **Exchange Online**. Those are two distinct
 * resources (`graph.microsoft.com` vs `outlook.office.com`), and Microsoft's token endpoint issues
 * an access token for one resource per request, so a single consent grants every scope and we mint
 * resource-specific access tokens from the one refresh token on demand. The "common" tenant accepts
 * both personal Microsoft accounts and work/school (Microsoft 365).
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

    /**
     * Builds the browser/Custom-Tab intent that starts the Microsoft sign-in.
     *
     * Throws [android.content.ActivityNotFoundException] when no usable browser is installed;
     * callers must guard the launch and surface that as an error rather than crashing.
     */
    fun createAuthIntent(): Intent {
        val request = AuthorizationRequest.Builder(
            serviceConfig,
            BuildConfig.OUTLOOK_OAUTH_CLIENT_ID,
            ResponseTypeValues.CODE,
            Uri.parse(BuildConfig.OUTLOOK_OAUTH_REDIRECT_URI),
        )
            // One consent covering both resources; per-resource access tokens are minted later.
            .setScope("openid email $OFFLINE $GRAPH_SCOPE $OUTLOOK_SCOPE")
            .build()
        // The returned intent is self-contained, so dispose the service (and its Custom-Tabs
        // warmup binding) immediately instead of leaking one per button tap.
        val service = AuthorizationService(context)
        return try {
            service.getAuthorizationRequestIntent(request)
        } finally {
            service.dispose()
        }
    }

    suspend fun exchangeToken(responseIntent: Intent): OAuthResult {
        val response = AuthorizationResponse.fromIntent(responseIntent)
        val exception = AuthorizationException.fromIntent(responseIntent)
        if (response == null) throw exception ?: IllegalStateException("Authorization was cancelled")
        val authCode = response.authorizationCode
            ?: throw exception ?: IllegalStateException("No authorization code was returned")

        // Microsoft issues one access token per resource, so the authorization-code exchange must
        // name a single resource. AppAuth's createTokenExchangeRequest() sends no scope, which makes
        // Microsoft reject our multi-resource consent code with AADSTS70011 ("must include a 'scope'
        // input parameter"). Request the OIDC scopes plus the Exchange Online resource so we still get
        // an id_token (for the email) and a refresh token to mint the Graph token from later.
        // This mirrors every field createTokenExchangeRequest() sets — including the nonce, which
        // AppAuth checks against the id_token's nonce claim (omitting it fails id_token validation).
        val exchangeRequest = TokenRequest.Builder(serviceConfig, BuildConfig.OUTLOOK_OAUTH_CLIENT_ID)
            .setGrantType(GrantTypeValues.AUTHORIZATION_CODE)
            .setAuthorizationCode(authCode)
            .setRedirectUri(Uri.parse(BuildConfig.OUTLOOK_OAUTH_REDIRECT_URI))
            .setCodeVerifier(response.request.codeVerifier)
            .setNonce(response.request.nonce)
            .setScope("openid email $OFFLINE $OUTLOOK_SCOPE")
            .build()

        val service = AuthorizationService(context)
        try {
            val tokenResponse = suspendCancellableCoroutine { continuation ->
                service.performTokenRequest(exchangeRequest) { token, error ->
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
            // Mint an Exchange Online token so the caller can verify the account over IMAP.
            val outlook = refreshForScope(authState, OUTLOOK_SCOPE)
            return OAuthResult(
                email = email,
                accessToken = outlook.accessToken,
                authStateJson = outlook.authStateJson,
            )
        } finally {
            service.dispose()
        }
    }

    /** A fresh Exchange Online (outlook.office.com) token for IMAP receive and SMTP-fallback send. */
    suspend fun freshOutlookToken(authStateJson: String): FreshToken =
        refreshForScope(AuthState.jsonDeserialize(authStateJson), OUTLOOK_SCOPE)

    /** A fresh Microsoft Graph token for the primary `sendMail` send path. */
    suspend fun freshGraphToken(authStateJson: String): FreshToken =
        refreshForScope(AuthState.jsonDeserialize(authStateJson), GRAPH_SCOPE)

    /** Redeems the stored refresh token for an access token scoped to a single resource. */
    private suspend fun refreshForScope(authState: AuthState, scope: String): FreshToken {
        val refreshToken = authState.refreshToken
            ?: throw IllegalStateException("No refresh token available; please sign in again")
        val service = AuthorizationService(context)
        try {
            val request = TokenRequest.Builder(serviceConfig, BuildConfig.OUTLOOK_OAUTH_CLIENT_ID)
                .setGrantType(GrantTypeValues.REFRESH_TOKEN)
                .setRefreshToken(refreshToken)
                .setScope("$OFFLINE $scope")
                .build()
            val tokenResponse = suspendCancellableCoroutine { continuation ->
                service.performTokenRequest(request) { token, error ->
                    if (token != null) {
                        continuation.resume(token)
                    } else {
                        continuation.resumeWithException(error ?: IllegalStateException("Token refresh failed"))
                    }
                }
            }
            authState.update(tokenResponse, null)
            return FreshToken(
                accessToken = tokenResponse.accessToken.orEmpty(),
                authStateJson = authState.jsonSerializeString(),
                accessTokenExpiry = tokenResponse.accessTokenExpirationTime,
            )
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

    private companion object {
        const val OFFLINE = "offline_access"
        const val GRAPH_SCOPE = "https://graph.microsoft.com/Mail.Send"
        const val OUTLOOK_SCOPE =
            "https://outlook.office.com/IMAP.AccessAsUser.All https://outlook.office.com/SMTP.Send"
    }
}
