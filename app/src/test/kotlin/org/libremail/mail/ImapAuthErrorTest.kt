// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetupTest
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.MessagingException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.domain.model.ImapConnectionParams
import org.libremail.domain.model.MailSecurity
import java.io.IOException
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the #390 error classifier: representative provider "IMAP is disabled" `AUTHENTICATE`
 * rejections map to the distinct IMAP-disabled outcome, while ordinary wrong-password / expired-token /
 * network failures do not (so the actionable "turn on IMAP" prompt never hijacks a real credential
 * error). The provider-text cases use constructed exceptions carrying the real server wording; the
 * "wrong password is not misclassified" case is proven end to end against a real GreenMail IMAP server.
 */
class ImapAuthErrorTest {

    // --- Provider "IMAP disabled" server text (works regardless of auth mechanism) ---

    @Test
    fun `Gmail not-enabled-for-IMAP text is classified as disabled`() {
        // Gmail's verbatim rejection when IMAP is off in its settings.
        val error = AuthenticationFailedException(
            "[ALERT] Your account is not enabled for IMAP use. Please visit your Gmail settings page " +
                "and enable your account for IMAP access. (Failure)",
        )
        assertTrue(ImapAuthError.isImapDisabled(error, usedOAuth = false))
    }

    @Test
    fun `explicit IMAP access is disabled text is classified as disabled`() {
        assertTrue(
            ImapAuthError.isImapDisabled(
                AuthenticationFailedException("IMAP access is disabled for your account."),
                usedOAuth = false,
            ),
        )
    }

    @Test
    fun `IMAP is disabled text is classified as disabled`() {
        assertTrue(ImapAuthError.isImapDisabled(MessagingException("IMAP is disabled"), usedOAuth = false))
    }

    @Test
    fun `IMAP access not enabled text is classified as disabled`() {
        assertTrue(
            ImapAuthError.isImapDisabled(
                AuthenticationFailedException("IMAP access is not enabled for this account"),
                usedOAuth = false,
            ),
        )
    }

    @Test
    fun `please enable IMAP text is classified as disabled`() {
        assertTrue(
            ImapAuthError.isImapDisabled(
                AuthenticationFailedException("Login failed. Please enable IMAP for your mailbox."),
                usedOAuth = false,
            ),
        )
    }

    @Test
    fun `disabled text in a wrapped cause is still classified as disabled`() {
        val wrapped = RuntimeException("Adding account failed", AuthenticationFailedException("IMAP is disabled"))
        assertTrue(ImapAuthError.isImapDisabled(wrapped, usedOAuth = false))
    }

    // --- Outlook OAuth inference: valid token + generic AUTHENTICATE rejection == IMAP off ---

    @Test
    fun `a generic AUTHENTICATE failure on the XOAUTH2 path is inferred as disabled`() {
        // outlook.office365.com returns only "AUTHENTICATE failed" with no distinctive text; the fresh
        // token was already accepted at exchange, so this means IMAP is off (issue #390).
        val error = AuthenticationFailedException("AUTHENTICATE failed")
        assertTrue(ImapAuthError.isImapDisabled(error, usedOAuth = true))
    }

    @Test
    fun `a wrapped AUTHENTICATE failure on the XOAUTH2 path is inferred as disabled`() {
        val wrapped = MessagingException("connect failed", AuthenticationFailedException("AUTHENTICATE failed"))
        assertTrue(ImapAuthError.isImapDisabled(wrapped, usedOAuth = true))
    }

    // --- Negatives: ordinary auth/other failures must NOT be misclassified ---

    @Test
    fun `a generic AUTHENTICATE failure without OAuth is not classified as disabled`() {
        // The password/app-password path: a plain "AUTHENTICATE failed" is a wrong password, not
        // IMAP-off — misclassifying it would send the user to the wrong fix.
        val error = AuthenticationFailedException("AUTHENTICATE failed")
        assertFalse(ImapAuthError.isImapDisabled(error, usedOAuth = false))
    }

    @Test
    fun `an invalid-credentials failure is not classified as disabled`() {
        assertFalse(
            ImapAuthError.isImapDisabled(
                AuthenticationFailedException("[AUTHENTICATIONFAILED] Invalid credentials (Failure)"),
                usedOAuth = false,
            ),
        )
        // Even on the OAuth path, an explicit invalid-credentials message is a token problem, not IMAP.
        assertFalse(
            ImapAuthError.isImapDisabled(
                MessagingException("Invalid credentials, please re-authenticate"),
                usedOAuth = true,
            ),
        )
    }

    @Test
    fun `a token-exchange failure on the OAuth path is not classified as disabled`() {
        // A token/consent failure is not a jakarta.mail AuthenticationFailedException, so the inference
        // must not fire even with usedOAuth = true (the fix there is re-auth, not enabling IMAP).
        assertFalse(ImapAuthError.isImapDisabled(IllegalStateException("Token exchange failed"), usedOAuth = true))
    }

    @Test
    fun `a plain AUTHENTICATE-failed string that is not an auth exception is not inferred`() {
        // The OAuth inference keys on the AuthenticationFailedException type, not the words
        // "AUTHENTICATE failed" appearing in some other exception's message.
        assertFalse(ImapAuthError.isImapDisabled(IOException("AUTHENTICATE failed"), usedOAuth = true))
    }

    @Test
    fun `a network failure on the OAuth path is not classified as disabled`() {
        assertFalse(ImapAuthError.isImapDisabled(IOException("Connection reset"), usedOAuth = true))
    }

    @Test
    fun `an IMAP host name in the message alone is not classified as disabled`() {
        // "imap.gmail.com" contains "imap" but no disabled/off wording, so it must not match.
        assertFalse(
            ImapAuthError.isImapDisabled(
                AuthenticationFailedException("login to imap.gmail.com failed"),
                usedOAuth = false,
            ),
        )
    }

    // --- End-to-end: a real GreenMail wrong-password rejection is not misclassified ---

    private lateinit var greenMail: GreenMail
    private val client = ImapClient(reuseConnections = false)

    @Before
    fun setUp() {
        greenMail = GreenMail(ServerSetupTest.SMTP_IMAP)
        greenMail.start()
        greenMail.setUser("alice@example.org", "secret")
        // listFolders breadcrumbs via AppLog -> android.util.Log, a throwing stub under plain JVM tests;
        // mock it fully-qualified so this file never imports android.util.Log (detekt ForbiddenImport).
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        greenMail.stop()
        unmockkAll()
    }

    @Test
    fun `a real wrong-password IMAP rejection is not classified as disabled`() = runTest {
        val params = ImapConnectionParams(
            host = "127.0.0.1",
            port = greenMail.imap.port,
            security = MailSecurity.NONE,
            username = "alice@example.org",
            secret = "wrong-password",
            useXoauth2 = false,
        )
        val error = runCatching { client.listFolders(params) }.exceptionOrNull()
        assertTrue(error != null, "GreenMail should reject the wrong password")
        assertFalse(ImapAuthError.isImapDisabled(error, usedOAuth = false))
    }
}
