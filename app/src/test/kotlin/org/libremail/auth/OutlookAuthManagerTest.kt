// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.auth

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.text.TextUtils
import android.util.Base64
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest
import net.openid.appauth.TokenResponse
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.libremail.BuildConfig
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Drives the Outlook/Microsoft OAuth wrapper in a pure JVM test. The AppAuth types it builds
 * (requests, responses, token responses) carry data in final fields, so those are constructed for
 * real; only the boundaries are faked — the Android statics AppAuth reaches for ([Uri], [Base64],
 * [TextUtils], [Intent]), the browser-less [PackageManager] that lets [AuthorizationService]
 * construct, and the token-endpoint call itself (its constructor is mocked, the callback invoked with
 * a canned [TokenResponse]). This pins the two-resource token exchange (Exchange token minted from the
 * auth-code grant), the email extraction from the id_token (with its preferred_username fallback), the
 * refresh paths, and every failure branch — none of which had unit cover before.
 */
class OutlookAuthManagerTest {

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `isConfigured reflects whether a client id ships with the build`() {
        installStatics()
        assertEquals(BuildConfig.OUTLOOK_OAUTH_CLIENT_ID.isNotBlank(), authManager().isConfigured)
    }

    @Test
    fun `createAuthIntent builds and returns the AppAuth sign-in intent`() {
        installStatics()
        mockkConstructor(AuthorizationService::class)
        every { anyConstructed<AuthorizationService>().dispose() } just runs
        val intent = mockk<Intent>()
        every { anyConstructed<AuthorizationService>().getAuthorizationRequestIntent(any()) } returns intent

        assertEquals(intent, authManager().createAuthIntent())
    }

    @Test
    fun `exchangeToken builds the result from the code-exchange token without a second refresh`() = runTest {
        installStatics()
        stubResponse(authResponseWithCode("auth-code"))
        stubTokenRequests(
            tokenResponse(access = "code-access", idToken = jwt("email" to "me@example.com"), refresh = "durable-rt"),
        )

        val result = authManager().exchangeToken(mockk<Intent>())

        assertEquals("me@example.com", result.email)
        // The code exchange already named the Exchange Online resource, so its access token is used
        // directly for IMAP verification — not a token from a second, redundant refresh round-trip.
        assertEquals("code-access", result.accessToken)
        // The durable AuthState — carrying the refresh token for later token refreshes — is persisted.
        assertTrue(result.authStateJson.contains("durable-rt"))
        // Exactly one token request (the code exchange); the redundant second refresh is gone.
        verify(exactly = 1) { anyConstructed<AuthorizationService>().performTokenRequest(any(), any()) }
    }

    @Test
    fun `exchangeToken falls back to preferred_username when the email claim is blank`() = runTest {
        installStatics()
        stubResponse(authResponseWithCode("auth-code"))
        stubTokenRequests(
            tokenResponse("code-access", jwt("email" to "", "preferred_username" to "alt@example.com"), "rt"),
        )

        assertEquals("alt@example.com", authManager().exchangeToken(mockk<Intent>()).email)
    }

    @Test
    fun `exchangeToken throws when the authorization was cancelled`() = runTest {
        installStatics()
        mockkStatic(AuthorizationResponse::class)
        every { AuthorizationResponse.fromIntent(any()) } returns null
        mockkStatic(AuthorizationException::class)
        every { AuthorizationException.fromIntent(any()) } returns null

        assertFailsWith<IllegalStateException> { authManager().exchangeToken(mockk<Intent>()) }
    }

    @Test
    fun `exchangeToken throws when no authorization code was returned`() = runTest {
        installStatics()
        stubResponse(authResponseWithCode(null)) // a response with no code
        mockkConstructor(AuthorizationService::class)
        every { anyConstructed<AuthorizationService>().dispose() } just runs

        assertFailsWith<IllegalStateException> { authManager().exchangeToken(mockk<Intent>()) }
    }

    @Test
    fun `exchangeToken throws when the id_token carries no usable email`() = runTest {
        installStatics()
        stubResponse(authResponseWithCode("auth-code"))
        stubTokenRequests(tokenResponse("code-access", jwt(), "rt")) // empty claims -> no email

        assertFailsWith<IllegalStateException> { authManager().exchangeToken(mockk<Intent>()) }
    }

    @Test
    fun `exchangeToken throws on an unparseable id_token`() = runTest {
        installStatics()
        stubResponse(authResponseWithCode("auth-code"))
        stubTokenRequests(tokenResponse("code-access", idToken = "not-a-jwt", refresh = "rt"))

        assertFailsWith<IllegalStateException> { authManager().exchangeToken(mockk<Intent>()) }
    }

    @Test
    fun `freshOutlookToken refreshes and returns the Exchange access token`() = runTest {
        installStatics()
        stubDeserializedAuthState(refreshToken = "rt")
        stubTokenRequests(tokenResponse("exchange-at", idToken = null, refresh = "rt"))

        val fresh = authManager().freshOutlookToken("{stored}")

        assertEquals("exchange-at", fresh.accessToken)
        assertEquals("{serialized}", fresh.authStateJson)
    }

    @Test
    fun `freshGraphToken refreshes and returns the Graph access token`() = runTest {
        installStatics()
        stubDeserializedAuthState(refreshToken = "rt")
        stubTokenRequests(tokenResponse("graph-at", idToken = null, refresh = "rt"))

        assertEquals("graph-at", authManager().freshGraphToken("{stored}").accessToken)
    }

    @Test
    fun `a refresh without a stored refresh token asks the user to sign in again`() = runTest {
        installStatics()
        stubDeserializedAuthState(refreshToken = null)
        mockkConstructor(AuthorizationService::class)
        every { anyConstructed<AuthorizationService>().dispose() } just runs

        assertFailsWith<IllegalStateException> { authManager().freshGraphToken("{stored}") }
    }

    @Test
    fun `exchangeToken surfaces a token endpoint failure`() = runTest {
        installStatics()
        stubResponse(authResponseWithCode("auth-code"))
        stubFailingTokenRequest()

        assertFailsWith<Exception> { authManager().exchangeToken(mockk<Intent>()) }
    }

    @Test
    fun `a refresh surfaces a token endpoint failure`() = runTest {
        installStatics()
        stubDeserializedAuthState(refreshToken = "rt")
        stubFailingTokenRequest()

        assertFailsWith<Exception> { authManager().freshGraphToken("{stored}") }
    }

    // --- test fixtures -----------------------------------------------------------------------------

    private fun authManager() = OutlookAuthManager(androidContext())

    /** Installs the Android statics AppAuth touches so its real objects can be built off-device. */
    private fun installStatics() {
        mockkStatic(TextUtils::class)
        every { TextUtils.isEmpty(any()) } answers { (firstArg<CharSequence?>()?.length ?: 0) == 0 }
        every { TextUtils.join(any(), any<Iterable<*>>()) } answers {
            secondArg<Iterable<*>>().joinToString(firstArg<CharSequence>().toString())
        }
        mockkStatic(Uri::class)
        val uri = mockk<Uri>(relaxed = true)
        every { uri.scheme } returns "org.libremail"
        every { Uri.parse(any()) } returns uri
        every { Uri.fromParts(any(), any(), any()) } returns uri
        mockkStatic(Base64::class)
        every { Base64.encodeToString(any(), any()) } answers {
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(firstArg())
        }
        every { Base64.decode(any<String>(), any()) } answers {
            java.util.Base64.getUrlDecoder().decode(firstArg<String>())
        }
        // BrowserSelector's static init builds a probe Intent; keep its fluent chain from touching stubs.
        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().setAction(any()) } answers { self as Intent }
        every { anyConstructed<Intent>().addCategory(any()) } answers { self as Intent }
        every { anyConstructed<Intent>().setData(any()) } answers { self as Intent }
    }

    /** A context whose PackageManager reports no browsers, so AuthorizationService constructs cleanly. */
    private fun androidContext(): Context {
        val pm = mockk<PackageManager>(relaxed = true)
        every { pm.resolveActivity(any(), any<Int>()) } returns null
        every { pm.queryIntentActivities(any(), any<Int>()) } returns emptyList()
        return mockk<Context>(relaxed = true).also {
            every { it.packageManager } returns pm
            every { it.applicationContext } returns it
        }
    }

    private val config get() = AuthorizationServiceConfiguration(Uri.parse("authorize"), Uri.parse("token"))

    private fun stubResponse(response: AuthorizationResponse) {
        mockkStatic(AuthorizationResponse::class)
        every { AuthorizationResponse.fromIntent(any()) } returns response
        mockkStatic(AuthorizationException::class)
        every { AuthorizationException.fromIntent(any()) } returns null
    }

    private fun authResponseWithCode(code: String?): AuthorizationResponse {
        val request = AuthorizationRequest.Builder(config, "client", ResponseTypeValues.CODE, Uri.parse("redirect"))
            .setScope("openid")
            .build()
        return AuthorizationResponse.Builder(request).apply { code?.let { setAuthorizationCode(it) } }.build()
    }

    /** Mocks the token-endpoint call, handing back [responses] in order to each performTokenRequest. */
    private fun stubTokenRequests(vararg responses: TokenResponse) {
        mockkConstructor(AuthorizationService::class)
        every { anyConstructed<AuthorizationService>().dispose() } just runs
        val queue = ArrayDeque(responses.toList())
        every { anyConstructed<AuthorizationService>().performTokenRequest(any(), any()) } answers {
            secondArg<AuthorizationService.TokenResponseCallback>().onTokenRequestCompleted(queue.removeFirst(), null)
        }
    }

    /** Mocks the token endpoint to report failure (null response, null exception) to its callback. */
    private fun stubFailingTokenRequest() {
        mockkConstructor(AuthorizationService::class)
        every { anyConstructed<AuthorizationService>().dispose() } just runs
        every { anyConstructed<AuthorizationService>().performTokenRequest(any(), any()) } answers {
            secondArg<AuthorizationService.TokenResponseCallback>().onTokenRequestCompleted(null, null)
        }
    }

    /** Makes AuthState.jsonDeserialize hand back a mock carrying [refreshToken]. */
    private fun stubDeserializedAuthState(refreshToken: String?) {
        val authState = mockk<AuthState>(relaxed = true)
        every { authState.refreshToken } returns refreshToken
        every { authState.update(any<TokenResponse>(), any()) } just runs
        every { authState.jsonSerializeString() } returns "{serialized}"
        mockkStatic(AuthState::class)
        every { AuthState.jsonDeserialize(any<String>()) } returns authState
    }

    private fun tokenResponse(access: String, idToken: String?, refresh: String?): TokenResponse {
        val request = TokenRequest.Builder(config, "client")
            .setGrantType("authorization_code")
            .setAuthorizationCode("code")
            .setRedirectUri(Uri.parse("redirect"))
            .build()
        return TokenResponse.Builder(request)
            .setAccessToken(access)
            .setIdToken(idToken)
            .setRefreshToken(refresh)
            .setAccessTokenExpirationTime(System.currentTimeMillis() + 3_600_000L)
            .build()
    }

    private fun jwt(vararg claims: Pair<String, String>): String {
        val payload = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(JSONObject(mapOf(*claims)).toString().toByteArray())
        return "header.$payload.signature"
    }
}
