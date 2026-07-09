// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.libremail.domain.model.Account
import org.libremail.domain.model.MailProvider
import org.libremail.reporting.AppLog
import org.libremail.reporting.accountLogRef
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Proactive per-account concurrency cap for iCloud Mail (issue #363): the provider-specific policy that
 * keeps LibreMail's own connect-per-operation IMAP traffic ([org.libremail.mail.ImapClient]) from ever
 * requesting more than [maxConcurrentConnections] connections at once for one iCloud account. Apple
 * documents a 5-8 concurrent-connection ceiling per account; production pins to the conservative low end
 * ([MAX_CONCURRENT_CONNECTIONS]) so LibreMail never approaches it even when backfill, an interactive
 * fetch, IDLE, and a send happen to overlap.
 *
 * This is the *proactive* counterpart to the two mechanisms issues #360/#356 already built for the
 * shared sync engine — composing with them rather than duplicating either:
 *  - [AccountThrottleGate] (#360) reacts *after* a provider has already throttled or locked the account;
 *    this gate keeps LibreMail's own request pattern from approaching that point in the first place.
 *    [MailBackfiller] consults both: the throttle gate first (skip an already-throttled account for the
 *    rest of the slice), then this one around each connection it actually opens.
 *  - [BackfillPacer] (#356) paces *how often* a new backfill slice starts; this gate bounds *how many*
 *    connections may be open at once within a slice — a different axis, so the two never fight.
 *
 * A no-op for every other provider: [withPermit] resolves [account]'s IMAP host to a [MailProvider] and
 * only acquires a permit for [MailProvider.ICLOUD] — Gmail/Yahoo/AOL/Outlook accounts run [block]
 * immediately, unconstrained (their own caps are issues #361/#362/#364's concern, kept as their own
 * provider-scoped policy rather than a shared table, so the four tickets land independently).
 *
 * State is a per-account [Semaphore], created on first use and kept for the process lifetime (a handful
 * of accounts at most, so this never grows unbounded) — in-process only, like every other gate in this
 * package; a process restart simply starts every account back at full availability.
 */
@Singleton
class IcloudConnectionLimiter internal constructor(private val maxConcurrentConnections: Int) {

    /** Production wiring: the conservative, documented-cap-respecting default. */
    @Inject constructor() : this(MAX_CONCURRENT_CONNECTIONS)

    private val permits = ConcurrentHashMap<String, Semaphore>()

    /**
     * Runs [block] holding one of [account]'s connection permits when it is an iCloud account —
     * suspending until one frees rather than rejecting, so a caller at the cap simply waits its turn
     * instead of failing. Every other provider's account runs [block] immediately with no gating. The
     * permit is always released, even if [block] throws (via [kotlinx.coroutines.sync.withPermit]), so a
     * failed fetch can never strand another caller waiting forever.
     */
    suspend fun <T> withPermit(account: Account, block: suspend () -> T): T {
        if (MailProvider.forImapHost(account.imap.host) != MailProvider.ICLOUD) return block()
        val semaphore = permits.computeIfAbsent(account.id) { Semaphore(maxConcurrentConnections) }
        if (semaphore.availablePermits == 0) {
            AppLog.i(TAG, "${accountLogRef(account.id)} connection cap reached; waiting for a permit")
        }
        return semaphore.withPermit { block() }
    }

    companion object {
        private const val TAG = "IcloudConnLimit"

        /**
         * Apple documents 5-8 concurrent IMAP connections per account (issue #363); pinned to the
         * conservative low end rather than the documented maximum. `internal` so tests can assert the
         * production cap without a magic number.
         */
        internal const val MAX_CONCURRENT_CONNECTIONS = 5
    }
}
