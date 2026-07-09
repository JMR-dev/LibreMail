// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail

import org.junit.Test
import org.libremail.domain.model.MailProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [ProviderAuthPolicy] must gate **only** Yahoo/AOL — the providers with the ~1-hour auth lockout
 * (issue #362) — with the conservative, lockout-avoiding cadence, and leave every other host
 * ([AuthCadencePolicy.DISABLED]) so siblings #361/#363/#364 and manual servers are unaffected. It must
 * also expose the documented connection / folder-index ceilings, and its whole schedule must stay under
 * the ~1-hour lockout window it protects.
 */
class ProviderAuthPolicyTest {

    /** The real IMAP host a provider's accounts use — the single source of truth, not a hard-coded string. */
    private fun hostOf(provider: MailProvider) = provider.createAccount("user@example.com").imap.host

    @Test
    fun `yahoo and aol get the enabled lockout-avoiding policy`() {
        for (provider in listOf(MailProvider.YAHOO, MailProvider.AOL)) {
            val policy = ProviderAuthPolicy.forHost(hostOf(provider))
            assertTrue(policy.enabled, "${provider.displayName} must be gated against its 1-hour auth lockout")
            assertEquals(ProviderAuthPolicy.YAHOO_AUTH_BACKOFF_BASE_MS, policy.baseBackoffMillis)
            assertEquals(ProviderAuthPolicy.YAHOO_AUTH_BACKOFF_MAX_MS, policy.maxBackoffMillis)
            assertEquals(ProviderAuthPolicy.YAHOO_AUTH_CIRCUIT_OPEN_THRESHOLD, policy.circuitOpenThreshold)
            assertEquals(ProviderAuthPolicy.YAHOO_AUTH_CIRCUIT_OPEN_MS, policy.circuitOpenMillis)
            assertEquals(ProviderAuthPolicy.YAHOO_MAX_CONCURRENT_CONNECTIONS, policy.maxConcurrentConnections)
            assertEquals(ProviderAuthPolicy.YAHOO_FOLDER_INDEX_CAP, policy.folderIndexCap)
        }
    }

    @Test
    fun `gmail and icloud are not gated`() {
        for (provider in listOf(MailProvider.GMAIL, MailProvider.ICLOUD)) {
            assertFalse(
                ProviderAuthPolicy.forHost(hostOf(provider)).enabled,
                "${provider.displayName} has no 1-hour auth lockout; issue #362 must leave it ungated",
            )
        }
    }

    @Test
    fun `outlook, unknown, and blank hosts are not gated`() {
        assertFalse(ProviderAuthPolicy.forHost("outlook.office365.com").enabled)
        assertFalse(ProviderAuthPolicy.forHost("imap.example.com").enabled)
        assertFalse(ProviderAuthPolicy.forHost("").enabled)
    }

    @Test
    fun `the disabled policy can never block a login`() {
        // A disabled policy's threshold is unreachable and its windows are zero, so a non-Yahoo/AOL
        // account is provably never gated regardless of how [AuthBackoff] is called.
        assertFalse(AuthCadencePolicy.DISABLED.enabled)
        assertEquals(Int.MAX_VALUE, AuthCadencePolicy.DISABLED.circuitOpenThreshold)
        assertEquals(0L, AuthBackoff.blockMillis(AuthCadencePolicy.DISABLED, consecutiveFailures = 1, random = 1.0))
    }

    @Test
    fun `the whole yahoo schedule stays under the one-hour lockout window`() {
        // Recovery must beat the lockout: every wait the policy can impose is under an hour, so a
        // recovered account resumes far sooner than a self-inflicted hour of silence.
        assertTrue(ProviderAuthPolicy.YAHOO_AUTH_BACKOFF_BASE_MS < ONE_HOUR_MS)
        assertTrue(ProviderAuthPolicy.YAHOO_AUTH_BACKOFF_MAX_MS < ONE_HOUR_MS)
        assertTrue(ProviderAuthPolicy.YAHOO_AUTH_CIRCUIT_OPEN_MS < ONE_HOUR_MS)
    }

    @Test
    fun `the documented connection and folder-index ceilings are exposed`() {
        // Reuse (issues #125/#357, ON by default) keeps an account to ~1 warm IMAP socket + at most one
        // IDLE connection = 2, comfortably under this documented ceiling of 5.
        assertEquals(5, ProviderAuthPolicy.YAHOO_MAX_CONCURRENT_CONNECTIONS)
        assertEquals(10_000, ProviderAuthPolicy.YAHOO_FOLDER_INDEX_CAP)
    }

    private companion object {
        const val ONE_HOUR_MS = 60 * 60_000L
    }
}
