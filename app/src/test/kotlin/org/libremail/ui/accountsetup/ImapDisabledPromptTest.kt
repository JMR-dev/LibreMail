// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.accountsetup

import jakarta.mail.AuthenticationFailedException
import org.junit.Test
import org.libremail.domain.model.Account
import org.libremail.domain.model.AuthType
import org.libremail.domain.model.MailProvider
import org.libremail.domain.model.MailSecurity
import org.libremail.domain.model.ServerConfig
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [imapDisabledPromptFor]: an "IMAP disabled" failure is turned into a provider-aware
 * prompt (brand copy + the provider's enable-IMAP help link when one is known), while any other failure
 * yields null so the caller keeps its generic error. Classification itself is covered by
 * [org.libremail.mail.ImapAuthErrorTest]; this pins the brand/URL resolution.
 */
class ImapDisabledPromptTest {

    private fun manualAccount(host: String) = Account(
        id = "imap:user@$host",
        email = "user@$host",
        displayName = "user@$host",
        authType = AuthType.PASSWORD_IMAP,
        imap = ServerConfig(host, 993, MailSecurity.SSL_TLS),
        smtp = ServerConfig(host, 465, MailSecurity.SSL_TLS),
    )

    @Test
    fun `outlook OAuth failure resolves to the Outlook brand and Microsoft help link`() {
        val prompt = imapDisabledPromptFor(
            AuthenticationFailedException("AUTHENTICATE failed"),
            Account.outlook("me@outlook.com"),
            usedOAuth = true,
        )

        assertEquals(MailProvider.OUTLOOK_BRAND, prompt?.brand)
        assertTrue(prompt?.helpUrl?.startsWith("https://support.microsoft.com/") == true, prompt?.helpUrl)
    }

    @Test
    fun `gmail app-password failure resolves to the Gmail brand and Google help link`() {
        val prompt = imapDisabledPromptFor(
            AuthenticationFailedException("Your account is not enabled for IMAP use"),
            MailProvider.GMAIL.createAccount("me@gmail.com"),
            usedOAuth = false,
        )

        assertEquals(MailProvider.GMAIL.displayName, prompt?.brand)
        assertTrue(prompt?.helpUrl?.startsWith("https://support.google.com/") == true, prompt?.helpUrl)
    }

    @Test
    fun `a manually-configured Gmail host is still recognised as Gmail`() {
        // brandFor resolves the brand from the IMAP host, so a manual account on imap.gmail.com gets
        // the Gmail copy + link even though it was set up through the generic path.
        val prompt = imapDisabledPromptFor(
            AuthenticationFailedException("IMAP is disabled"),
            manualAccount("imap.gmail.com"),
            usedOAuth = false,
        )

        assertEquals(MailProvider.GMAIL.displayName, prompt?.brand)
        assertTrue(prompt?.helpUrl?.startsWith("https://support.google.com/") == true, prompt?.helpUrl)
    }

    @Test
    fun `a Yahoo failure keeps the brand but offers no link`() {
        // Yahoo/iCloud/AOL gate access via app passwords, not a user-facing IMAP toggle we can deep-link.
        val prompt = imapDisabledPromptFor(
            AuthenticationFailedException("IMAP access is disabled"),
            MailProvider.YAHOO.createAccount("me@yahoo.com"),
            usedOAuth = false,
        )

        assertEquals(MailProvider.YAHOO.displayName, prompt?.brand)
        assertNull(prompt?.helpUrl)
    }

    @Test
    fun `an unknown host yields a generic prompt with no brand and no link`() {
        val prompt = imapDisabledPromptFor(
            AuthenticationFailedException("IMAP access is disabled"),
            manualAccount("mail.example.org"),
            usedOAuth = false,
        )

        assertNull(prompt?.brand)
        assertNull(prompt?.helpUrl)
    }

    @Test
    fun `a wrong-password failure yields no prompt`() {
        val prompt = imapDisabledPromptFor(
            AuthenticationFailedException("Invalid credentials"),
            MailProvider.GMAIL.createAccount("me@gmail.com"),
            usedOAuth = false,
        )

        assertNull(prompt)
    }

    @Test
    fun `ImapDisabledPrompt value semantics`() {
        val prompt = ImapDisabledPrompt(brand = "Outlook", helpUrl = "https://example.test/imap")
        assertEquals(prompt, prompt.copy())
        assertEquals(prompt.hashCode(), prompt.copy().hashCode())
        assertTrue(prompt.toString().contains("Outlook"))
    }
}
