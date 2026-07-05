// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.reporting

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [accountLogRef] turns a PII-bearing `Account.id` (which embeds the raw email) into a short, stable,
 * non-reversible reference that is safe to write to logs and a [DebugReport].
 */
class AccountLogRefTest {

    @Test
    fun `is deterministic and stable for the same id`() {
        val id = "outlook:user@example.com"
        assertEquals(accountLogRef(id), accountLogRef(id))
    }

    @Test
    fun `differs across different ids`() {
        assertNotEquals(
            accountLogRef("outlook:user@example.com"),
            accountLogRef("outlook:other@example.com"),
        )
        // Same address under a different scheme is a different account, so it must map to a different ref.
        assertNotEquals(
            accountLogRef("outlook:user@example.com"),
            accountLogRef("imap:user@example.com"),
        )
    }

    @Test
    fun `contains neither the email nor its domain and is not the raw id`() {
        val id = "imap:Alice.Smith@example.com"

        val ref = accountLogRef(id)

        assertNotEquals(id, ref)
        assertFalse(ref.contains("@"), ref)
        assertFalse(ref.contains("Alice.Smith"), ref)
        assertFalse(ref.contains("example.com"), ref)
        assertFalse(ref.contains("example"), ref)
    }

    @Test
    fun `keeps the non-PII scheme prefix so refs stay readable`() {
        assertTrue(accountLogRef("outlook:user@example.com").startsWith("outlook:"), "outlook prefix")
        assertTrue(accountLogRef("imap:user@example.com").startsWith("imap:"), "imap prefix")
    }

    @Test
    fun `falls back to a generic prefix for an id with no scheme, never leaking the address`() {
        val ref = accountLogRef("user@example.com")

        assertTrue(ref.startsWith("acct:"), ref)
        assertFalse(ref.contains("@"), ref)
        assertFalse(ref.contains("example"), ref)
    }

    @Test
    fun `never treats an address-shaped prefix as the scheme`() {
        // If the part before the first ':' is itself an address, it must not surface as the prefix.
        val ref = accountLogRef("user@example.com:143")

        assertTrue(ref.startsWith("acct:"), ref)
        assertFalse(ref.contains("@"), ref)
        assertFalse(ref.contains("example"), ref)
    }
}
