// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.domain.model

import org.junit.Test
import kotlin.test.assertEquals

/**
 * Locks down account-id normalization (issue #305): the id is the primary key, so casing that varies
 * between adds must not spawn a second account syncing the same mailbox. [normalizeEmailForAccountId]
 * always lowercases the domain and — for consumer providers — the whole address; the generic path
 * leaves the local part as typed. [Account.outlook] derives a deterministic id from the address.
 */
class AccountTest {

    @Test
    fun `normalize always lowercases the domain, keeping the local part when not a consumer provider`() {
        assertEquals("User@example.org", normalizeEmailForAccountId("User@Example.ORG", lowercaseLocalPart = false))
        assertEquals("ADA@gmail.com", normalizeEmailForAccountId("ADA@GMAIL.COM", lowercaseLocalPart = false))
    }

    @Test
    fun `normalize lowercases the whole address for consumer providers`() {
        assertEquals("user@example.org", normalizeEmailForAccountId("User@Example.ORG", lowercaseLocalPart = true))
        assertEquals("ada@gmail.com", normalizeEmailForAccountId("ADA@GMAIL.COM", lowercaseLocalPart = true))
    }

    @Test
    fun `normalize trims surrounding whitespace either way`() {
        assertEquals("user@example.org", normalizeEmailForAccountId("  user@Example.org  ", lowercaseLocalPart = false))
        assertEquals("user@example.org", normalizeEmailForAccountId("  User@Example.org  ", lowercaseLocalPart = true))
    }

    @Test
    fun `normalize maps different casings of one address to the same value`() {
        assertEquals(
            normalizeEmailForAccountId("User@Gmail.com", lowercaseLocalPart = true),
            normalizeEmailForAccountId("user@gmail.com", lowercaseLocalPart = true),
        )
    }

    @Test
    fun `normalize handles an address with no at-sign without throwing`() {
        // No domain to isolate: lowercased whole for consumer, left as-is for generic.
        assertEquals("root", normalizeEmailForAccountId("Root", lowercaseLocalPart = true))
        assertEquals("Root", normalizeEmailForAccountId("Root", lowercaseLocalPart = false))
    }

    @Test
    fun `outlook derives a lowercased, deterministic id but keeps the displayed email casing`() {
        val account = Account.outlook("User@Outlook.com")

        assertEquals("outlook:user@outlook.com", account.id)
        assertEquals("User@Outlook.com", account.email)
        assertEquals(AuthType.OAUTH_OUTLOOK, account.authType)
        // Any casing of the same address resolves to one id, so a re-auth never duplicates the account.
        assertEquals(Account.outlook("USER@OUTLOOK.COM").id, account.id)
    }
}
