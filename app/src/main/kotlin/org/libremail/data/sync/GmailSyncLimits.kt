// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import org.libremail.domain.model.Account
import org.libremail.domain.model.MailProvider

/**
 * Gmail's documented IMAP connection and bandwidth ceilings (issue #361) — the Gmail-specific config
 * that feeds the shared, provider-agnostic pacing machinery ([AccountThrottleGate]'s reactive backoff,
 * issue #360; [BackfillPacer]'s proactive inter-slice cooldown, issue #356) instead of reinventing
 * either. Per-account, per-provider connection/bandwidth caps were deliberately deferred out of both
 * (see [org.libremail.mail.ImapConnectionCache]'s "separate effort #356/#360-#364" note); this is
 * Gmail's slice of that follow-up. Kept additive and provider-scoped — the sibling Yahoo (#362), iCloud
 * (#363), and Outlook/Graph (#364) tickets land their own provider config independently.
 *
 * Values are Google's documented Gmail IMAP limits (as referenced by issue #361):
 *  - 15 max simultaneous IMAP connections per account.
 *  - 2,500 MB/day download, 500 MB/day upload.
 *  - 10,000 messages per label, 10,000 labels.
 *
 * Pure data + provider detection only — no state, no logging (mirrors how [ThrottleBackoff] and
 * [ThrottleClassifier] stay pure while [AccountThrottleGate] carries the state and logging). The
 * stateful counterpart that actually tracks bytes against [DAILY_DOWNLOAD_BUDGET_BYTES] is
 * [GmailBandwidthTracker].
 */
object GmailSyncLimits {
    /** Gmail's documented simultaneous-IMAP-connection ceiling, per account. */
    const val MAX_IMAP_CONNECTIONS = 15

    /**
     * Connections proactively reserved for interactive use (never spent by background sync/backfill),
     * mirroring the "interactive-request priority over backfill" lever from the #360 umbrella issue.
     * LibreMail's IMAP layer already keeps at most ONE reused connection per account
     * ([org.libremail.mail.ImapConnectionCache], issue #125/#357) plus one dedicated IMAP-IDLE
     * connection ([org.libremail.mail.ImapClient.idle]) — 2 total, well inside the resulting headroom
     * regardless of this value — so today this is documented config for any future pooling work rather
     * than something that needs active enforcement (see `GmailSyncLimitsTest` for the invariant that
     * ties the two together).
     */
    const val INTERACTIVE_RESERVED_CONNECTIONS = 1

    /** [MAX_IMAP_CONNECTIONS] minus [INTERACTIVE_RESERVED_CONNECTIONS] — background sync/backfill's budget. */
    const val MAX_BACKGROUND_IMAP_CONNECTIONS = MAX_IMAP_CONNECTIONS - INTERACTIVE_RESERVED_CONNECTIONS

    private const val BYTES_PER_MB = 1024L * 1024L

    /**
     * Gmail's documented daily download budget. [GmailBandwidthTracker] accumulates bytes actually
     * pulled by background prefetch (see
     * [org.libremail.data.repository.MailRepositoryImpl.prefetchMessage]) against this; [MailBackfiller]
     * and [MailSyncer] defer further prefetch for an account once it is reached, resuming automatically
     * the next day. Interactive fetches (opening a message, downloading a tapped attachment, loading
     * inline images) are never gated by it — the same interactive-priority principle #355/#360 already
     * apply elsewhere.
     */
    const val DAILY_DOWNLOAD_BUDGET_BYTES = 2_500L * BYTES_PER_MB

    /**
     * Gmail's documented daily upload budget (SMTP send). Captured here for completeness against
     * issue #361's documented limits; sending/composing is a separate path from this issue's
     * sync/backfill scope, so it is not enforced by this change.
     */
    const val DAILY_UPLOAD_BUDGET_BYTES = 500L * BYTES_PER_MB

    /** Gmail's documented per-label message ceiling. */
    const val MAX_MESSAGES_PER_LABEL = 10_000

    /** Gmail's documented total-labels ceiling. */
    const val MAX_LABELS = 10_000

    /**
     * True when [account]'s IMAP host resolves to [MailProvider.GMAIL] (including its legacy
     * `imap.googlemail.com` alias) — the single place issue #361's caps decide "is this Gmail".
     */
    fun appliesTo(account: Account): Boolean = MailProvider.forImapHost(account.imap.host) == MailProvider.GMAIL
}
