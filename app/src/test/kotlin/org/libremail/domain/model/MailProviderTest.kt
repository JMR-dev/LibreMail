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
