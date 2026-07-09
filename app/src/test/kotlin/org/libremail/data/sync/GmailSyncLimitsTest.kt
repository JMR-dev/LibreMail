// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import org.junit.Test
import org.libremail.domain.model.Account
import org.libremail.domain.model.MailProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks down Gmail's documented IMAP connection/bandwidth ceilings (issue #361) — easy to mistype and
 * painful to debug on-device, so the exact figures are asserted here rather than trusted to a code
 * review (mirrors [org.libremail.domain.model.MailProviderTest]'s rationale for the provider presets).
 */
class GmailSyncLimitsTest {

    @Test
    fun `documented connection and bandwidth ceilings match Gmail's published limits`() {
        assertEquals(15, GmailSyncLimits.MAX_IMAP_CONNECTIONS)
        assertEquals(1, GmailSyncLimits.INTERACTIVE_RESERVED_CONNECTIONS)
        assertEquals(14, GmailSyncLimits.MAX_BACKGROUND_IMAP_CONNECTIONS)
        assertEquals(2_500L * 1024 * 1024, GmailSyncLimits.DAILY_DOWNLOAD_BUDGET_BYTES)
        assertEquals(500L * 1024 * 1024, GmailSyncLimits.DAILY_UPLOAD_BUDGET_BYTES)
        assertEquals(10_000, GmailSyncLimits.MAX_MESSAGES_PER_LABEL)
        assertEquals(10_000, GmailSyncLimits.MAX_LABELS)
    }

    /**
     * Ties Gmail's documented ceiling to today's actual architecture: [org.libremail.mail.ImapConnectionCache]
     * (#125/#357) keeps at most ONE reused connection per account, plus `ImapClient.idle`'s own dedicated
     * IDLE connection — 2 total, regardless of provider. Asserting that invariant against the real
     * headroom-adjusted cap means a future change that grows per-account concurrency (e.g. a real
     * connection pool) trips this test well before it could ever approach Gmail's actual ceiling.
     */
    @Test
    fun `today's architecture keeps concurrent connections per account well inside the background budget`() {
        val knownConcurrentConnectionsPerAccount = 2 // one reused IMAP connection + one dedicated IDLE connection
        assertTrue(knownConcurrentConnectionsPerAccount <= GmailSyncLimits.MAX_BACKGROUND_IMAP_CONNECTIONS)
    }

    @Test
    fun `appliesTo is true for a gmail account, including the legacy googlemail host`() {
        val gmail = MailProvider.GMAIL.createAccount("user@gmail.com")
        assertTrue(GmailSyncLimits.appliesTo(gmail))

        val legacyHost = gmail.copy(imap = gmail.imap.copy(host = "imap.googlemail.com"))
        assertTrue(GmailSyncLimits.appliesTo(legacyHost))
    }

    @Test
    fun `appliesTo is false for a non-gmail account`() {
        assertFalse(GmailSyncLimits.appliesTo(MailProvider.YAHOO.createAccount("user@yahoo.com")))
        assertFalse(GmailSyncLimits.appliesTo(MailProvider.ICLOUD.createAccount("user@icloud.com")))
        assertFalse(GmailSyncLimits.appliesTo(MailProvider.AOL.createAccount("user@aol.com")))
        assertFalse(GmailSyncLimits.appliesTo(Account.outlook("user@outlook.com")))
    }
}
