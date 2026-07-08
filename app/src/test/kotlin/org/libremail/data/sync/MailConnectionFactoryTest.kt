// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.libremail.auth.FreshToken
import org.libremail.auth.OutlookAuthManager
import org.libremail.data.security.CredentialStore
import org.libremail.data.settings.AppSettings
import org.libremail.data.settings.SettingsRepository
import org.libremail.domain.model.Account
import org.libremail.domain.model.AuthType
import org.libremail.domain.model.MailSecurity
import org.libremail.domain.model.ServerConfig
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [MailConnectionFactory] turns a stored credential into connection params, refreshing (and caching)
 * OAuth access tokens on demand. These tests pin the password path, the OAuth refresh-and-cache
 * behaviour (a still-valid token is reused; an expired or unknown-expiry one is refreshed), the
 * persist-only-when-changed rule, and the missing-credential errors — all without a real network.
 */
class MailConnectionFactoryTest {

    private val credentialStore = mockk<CredentialStore>(relaxed = true)
    private val outlookAuthManager = mockk<OutlookAuthManager>()
    private val settingsRepository = mockk<SettingsRepository> {
        every { settings } returns flowOf(AppSettings())
    }

    private fun factory() = MailConnectionFactory(credentialStore, outlookAuthManager, settingsRepository)

    private val passwordAccount = Account(
        id = "acct",
        email = "a@example.org",
        displayName = "A",
        authType = AuthType.PASSWORD_IMAP,
        imap = ServerConfig("imap.example.org", 993, MailSecurity.SSL_TLS),
        smtp = ServerConfig("smtp.example.org", 465, MailSecurity.SSL_TLS),
    )

    private val outlookAccount = Account.outlook("me@example.com")

    private fun token(access: String, json: String, expiry: Long?) =
        FreshToken(accessToken = access, authStateJson = json, accessTokenExpiry = expiry)

    private val future get() = System.currentTimeMillis() + 3_600_000L

    @Test
    fun `password imapParams resolve the stored secret and never use XOAUTH2`() = runTest {
        coEvery { credentialStore.loadSecret("acct") } returns "app-password"

        val params = factory().imapParamsFor(passwordAccount)

        assertEquals("app-password", params.secret)
        assertEquals("a@example.org", params.username)
        assertFalse(params.useXoauth2)
        assertTrue(params.strictStartTls) // allowStartTls defaults false -> strict
    }

    @Test
    fun `password smtpParams resolve the stored secret`() = runTest {
        coEvery { credentialStore.loadSecret("acct") } returns "app-password"

        val params = factory().smtpParamsFor(passwordAccount)

        assertEquals("app-password", params.secret)
        assertFalse(params.useXoauth2)
    }

    @Test
    fun `a missing password credential is a hard error`() = runTest {
        coEvery { credentialStore.loadSecret("acct") } returns null

        // A typed MissingCredentialsException (#403), not a bare IllegalStateException, so the IMAP IDLE
        // watcher can catch it specifically and defer on the account-add write race.
        assertFailsWith<MissingCredentialsException> { factory().imapParamsFor(passwordAccount) }
    }

    @Test
    fun `allowing STARTTLS relaxes the strict flag`() = runTest {
        every { settingsRepository.settings } returns flowOf(AppSettings(allowStartTls = true))
        coEvery { credentialStore.loadSecret("acct") } returns "pw"

        assertFalse(factory().imapParamsFor(passwordAccount).strictStartTls)
    }

    @Test
    fun `outlook imapParams mint an Exchange token and mark XOAUTH2`() = runTest {
        coEvery { credentialStore.loadSecret(outlookAccount.id) } returns "stored"
        coEvery { outlookAuthManager.freshOutlookToken("stored") } returns token("outlook-at", "refreshed", future)

        val params = factory().imapParamsFor(outlookAccount)

        assertEquals("outlook-at", params.secret)
        assertTrue(params.useXoauth2)
        // The AuthState changed, so the refreshed one is persisted for next time.
        coVerify { credentialStore.saveSecret(outlookAccount.id, "refreshed") }
    }

    @Test
    fun `an unchanged AuthState is not re-persisted`() = runTest {
        coEvery { credentialStore.loadSecret(outlookAccount.id) } returns "stored"
        coEvery { outlookAuthManager.freshOutlookToken("stored") } returns token("outlook-at", "stored", future)

        factory().imapParamsFor(outlookAccount)

        coVerify(exactly = 0) { credentialStore.saveSecret(any(), any()) }
    }

    @Test
    fun `a still-valid cached token is reused without a second refresh`() = runTest {
        coEvery { credentialStore.loadSecret(outlookAccount.id) } returns "stored"
        coEvery { outlookAuthManager.freshOutlookToken("stored") } returns token("outlook-at", "stored", future)
        val factory = factory()

        factory.imapParamsFor(outlookAccount)
        val second = factory.imapParamsFor(outlookAccount)

        assertEquals("outlook-at", second.secret)
        coVerify(exactly = 1) { outlookAuthManager.freshOutlookToken(any()) }
    }

    @Test
    fun `an expired cached token forces a refresh`() = runTest {
        coEvery { credentialStore.loadSecret(outlookAccount.id) } returns "stored"
        val past = System.currentTimeMillis() - 1_000L
        coEvery { outlookAuthManager.freshOutlookToken("stored") } returns token("outlook-at", "stored", past)
        val factory = factory()

        factory.imapParamsFor(outlookAccount)
        factory.imapParamsFor(outlookAccount)

        coVerify(exactly = 2) { outlookAuthManager.freshOutlookToken(any()) }
    }

    @Test
    fun `an unknown expiry is never trusted from cache`() = runTest {
        coEvery { credentialStore.loadSecret(outlookAccount.id) } returns "stored"
        coEvery { outlookAuthManager.freshOutlookToken("stored") } returns token("outlook-at", "stored", null)
        val factory = factory()

        factory.imapParamsFor(outlookAccount)
        factory.imapParamsFor(outlookAccount)

        coVerify(exactly = 2) { outlookAuthManager.freshOutlookToken(any()) }
    }

    @Test
    fun `graphToken mints a Graph-scoped token cached separately from the Exchange one`() = runTest {
        coEvery { credentialStore.loadSecret(outlookAccount.id) } returns "stored"
        coEvery { outlookAuthManager.freshGraphToken("stored") } returns token("graph-at", "stored", future)
        coEvery { outlookAuthManager.freshOutlookToken("stored") } returns token("outlook-at", "stored", future)
        val factory = factory()

        assertEquals("graph-at", factory.graphTokenFor(outlookAccount))
        // The Exchange scope has its own cache slot, so it still refreshes independently.
        assertEquals("outlook-at", factory.imapParamsFor(outlookAccount).secret)
        coVerify(exactly = 1) { outlookAuthManager.freshGraphToken(any()) }
        coVerify(exactly = 1) { outlookAuthManager.freshOutlookToken(any()) }
    }

    @Test
    fun `a missing OAuth credential is a hard error`() = runTest {
        coEvery { credentialStore.loadSecret(outlookAccount.id) } returns null

        assertFailsWith<MissingCredentialsException> { factory().graphTokenFor(outlookAccount) }
    }
}
