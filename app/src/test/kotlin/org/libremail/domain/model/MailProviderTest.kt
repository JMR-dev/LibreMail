// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.domain.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks down the preconfigured server settings for the app-password vendors. These are easy to get
 * subtly wrong (a swapped port or the wrong transport security) and painful to debug on-device, so
 * they are asserted explicitly here rather than trusted to a code review.
 */
class MailProviderTest {

    @Test
    fun `gmail preset uses documented imap and starttls smtp endpoints`() {
        val account = MailProvider.GMAIL.createAccount("user@gmail.com")

        assertEquals("imap.gmail.com", account.imap.host)
        assertEquals(993, account.imap.port)
        assertEquals(MailSecurity.SSL_TLS, account.imap.security)

        assertEquals("smtp.gmail.com", account.smtp.host)
        assertEquals(587, account.smtp.port)
        assertEquals(MailSecurity.STARTTLS, account.smtp.security)
    }

    @Test
    fun `yahoo preset uses documented imap and implicit-tls smtp endpoints`() {
        val account = MailProvider.YAHOO.createAccount("user@yahoo.com")

        assertEquals("imap.mail.yahoo.com", account.imap.host)
        assertEquals(993, account.imap.port)
        assertEquals(MailSecurity.SSL_TLS, account.imap.security)

        assertEquals("smtp.mail.yahoo.com", account.smtp.host)
        assertEquals(465, account.smtp.port)
        assertEquals(MailSecurity.SSL_TLS, account.smtp.security)
    }

    @Test
    fun `icloud preset uses documented imap and starttls smtp endpoints`() {
        val account = MailProvider.ICLOUD.createAccount("user@icloud.com")

        assertEquals("imap.mail.me.com", account.imap.host)
        assertEquals(993, account.imap.port)
        assertEquals(MailSecurity.SSL_TLS, account.imap.security)

        assertEquals("smtp.mail.me.com", account.smtp.host)
        assertEquals(587, account.smtp.port)
        assertEquals(MailSecurity.STARTTLS, account.smtp.security)
    }

    @Test
    fun `aol preset uses documented imap and implicit-tls smtp endpoints`() {
        val account = MailProvider.AOL.createAccount("user@aol.com")

        assertEquals("imap.aol.com", account.imap.host)
        assertEquals(993, account.imap.port)
        assertEquals(MailSecurity.SSL_TLS, account.imap.security)

        assertEquals("smtp.aol.com", account.smtp.host)
        assertEquals(465, account.smtp.port)
        assertEquals(MailSecurity.SSL_TLS, account.smtp.security)
    }

    @Test
    fun `no provider ever uses insecure transport`() {
        MailProvider.entries.forEach { provider ->
            val account = provider.createAccount("user@example.com")
            assertTrue(
                account.imap.security != MailSecurity.NONE,
                "${provider.key} IMAP must not use MailSecurity.NONE",
            )
            assertTrue(
                account.smtp.security != MailSecurity.NONE,
                "${provider.key} SMTP must not use MailSecurity.NONE",
            )
        }
    }

    @Test
    fun `every provider resolves to a password-imap account with a help url`() {
        MailProvider.entries.forEach { provider ->
            val account = provider.createAccount("user@example.com")
            assertEquals(AuthType.PASSWORD_IMAP, account.authType)
            assertTrue(
                provider.appPasswordHelpUrl.startsWith("https://"),
                "${provider.key} must expose an https app-password help URL",
            )
        }
    }

    @Test
    fun `gmail and icloud link two-factor setup help over https, yahoo and aol do not`() {
        // Gmail's app-passwords page rejects accounts without 2-Step Verification, so its setup
        // screen must offer the setup article as a way out (issue #98).
        val gmailUrl = assertNotNull(
            MailProvider.GMAIL.twoFactorHelpUrl,
            "gmail must expose a 2-Step Verification help URL",
        )
        assertTrue(gmailUrl.startsWith("https://"), "gmail 2FA help URL must be https")

        // Apple also won't issue an app-specific password until two-factor authentication is on,
        // so iCloud needs the same escape hatch — pointed at Apple's dedicated article, not a
        // generic Apple ID sign-in page (issue #153).
        assertEquals("https://support.apple.com/en-us/102660", MailProvider.ICLOUD.twoFactorHelpUrl)

        // Yahoo gates nothing on two-factor — reconfirmed directly against Yahoo's own help docs,
        // which never list two-step verification as a prerequisite for generating an app password
        // (issue #155) — so it must not grow the extra link.
        assertNull(MailProvider.YAHOO.twoFactorHelpUrl)

        // AOL gates nothing on two-factor either — its app-password article never lists two-step
        // verification as a prerequisite, and its separate two-step-verification article doesn't
        // call itself one either (issue #156) — so it must not grow the extra link.
        assertNull(MailProvider.AOL.twoFactorHelpUrl)
    }

    @Test
    fun `icloud app-password help points at Apple's specific instructions, not the generic sign-in page`() {
        assertEquals("https://support.apple.com/en-us/102654", MailProvider.ICLOUD.appPasswordHelpUrl)
    }

    @Test
    fun `yahoo app-password help points at Yahoo's step-by-step instructions, not the generic sign-in page`() {
        assertEquals(
            "https://my.help.yahoo.com/kb/mail/generate-app-specific-password-sln15241.html",
            MailProvider.YAHOO.appPasswordHelpUrl,
        )
    }

    @Test
    fun `aol app-password help points at AOL's dedicated app-password article`() {
        assertEquals(
            "https://help.aol.com/articles/Create-and-manage-app-password",
            MailProvider.AOL.appPasswordHelpUrl,
        )
    }

    @Test
    fun `createAccount trims the email and derives a stable id and display name`() {
        val account = MailProvider.GMAIL.createAccount("  User@Gmail.com  ")

        assertEquals("User@Gmail.com", account.email)
        assertEquals("imap:User@Gmail.com", account.id)
        assertEquals("User@Gmail.com", account.displayName)
    }

    @Test
    fun `createAccount keeps an explicit non-blank display name`() {
        val account = MailProvider.GMAIL.createAccount("user@gmail.com", displayName = "Work")

        assertEquals("Work", account.displayName)
    }

    @Test
    fun `fromKey looks up providers case-insensitively and returns null for unknowns`() {
        assertEquals(MailProvider.GMAIL, MailProvider.fromKey("gmail"))
        assertEquals(MailProvider.YAHOO, MailProvider.fromKey("YAHOO"))
        assertEquals(MailProvider.ICLOUD, MailProvider.fromKey("iCloud"))
        assertEquals(MailProvider.AOL, MailProvider.fromKey("AOL"))
        assertNull(MailProvider.fromKey("outlook"))
        assertNull(MailProvider.fromKey(""))
    }

    @Test
    fun `provider keys are unique and lowercase`() {
        val keys = MailProvider.entries.map { it.key }
        assertEquals(keys.toSet().size, keys.size, "provider keys must be unique")
        keys.forEach { key -> assertEquals(key.lowercase(), key, "provider key must be lowercase") }
    }

    @Test
    fun `display names are present for the picker`() {
        MailProvider.entries.forEach { provider ->
            assertNotNull(provider.displayName)
            assertTrue(provider.displayName.isNotBlank())
        }
    }

    // #69: host→brand matching is consolidated here. forImapHost matches primary hosts and aliases;
    // brandFor is the single seam that adds Outlook (deliberately not a preset entry) to the mix.
    @Test
    fun `forImapHost matches primary hosts and Gmail's legacy alias, case-insensitively`() {
        assertEquals(MailProvider.GMAIL, MailProvider.forImapHost("imap.gmail.com"))
        assertEquals(MailProvider.GMAIL, MailProvider.forImapHost("imap.googlemail.com"))
        assertEquals(MailProvider.GMAIL, MailProvider.forImapHost("IMAP.GMAIL.COM"))
        assertEquals(MailProvider.ICLOUD, MailProvider.forImapHost("imap.mail.me.com"))
        // A manually configured AOL account (set up before this preset existed) must still resolve
        // to the AOL brand (issue #156).
        assertEquals(MailProvider.AOL, MailProvider.forImapHost("imap.aol.com"))
        assertNull(MailProvider.forImapHost("imap.example.org"))
    }

    @Test
    fun `matchesHost recognizes a provider's aliases but not unrelated hosts`() {
        assertTrue(MailProvider.GMAIL.matchesHost("imap.googlemail.com"))
        assertFalse(MailProvider.GMAIL.matchesHost("imap.mail.me.com"))
    }

    @Test
    fun `brandFor centralizes host to brand matching, including Outlook`() {
        assertEquals("Gmail", MailProvider.brandFor(account("imap.googlemail.com")))
        assertEquals("iCloud Mail", MailProvider.brandFor(account("imap.mail.me.com")))
        assertEquals("AOL Mail", MailProvider.brandFor(account("imap.aol.com")))
        // A manually configured Office 365 host is Outlook even without the OAuth auth type.
        assertEquals(MailProvider.OUTLOOK_BRAND, MailProvider.brandFor(account("outlook.office365.com")))
        // The OAuth auth type brands as Outlook regardless of host.
        assertEquals(
            MailProvider.OUTLOOK_BRAND,
            MailProvider.brandFor(account("example.com", AuthType.OAUTH_OUTLOOK)),
        )
        // Over-match guard: a host merely containing "outlook" maps to no brand.
        assertNull(MailProvider.brandFor(account("outlook.example.com")))
    }

    private fun account(imapHost: String, authType: AuthType = AuthType.PASSWORD_IMAP) = Account(
        id = "acct",
        email = "user@example.com",
        displayName = "user",
        authType = authType,
        imap = ServerConfig(imapHost, 993, MailSecurity.SSL_TLS),
        smtp = ServerConfig("smtp.example.com", 587, MailSecurity.STARTTLS),
    )
}
