// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail

import org.libremail.domain.model.MailProvider

/**
 * Per-provider IMAP limits and the **proactive auth cadence** for issue #362, keyed by IMAP host.
 *
 * This is the *config* half of the Yahoo/AOL work; the enforcing state machine is [AuthThrottleGate]
 * and the pure schedule is [AuthBackoff]. It deliberately parallels issue #360's *reactive* family
 * ([org.libremail.data.sync.ThrottleBackoff] / [org.libremail.data.sync.AccountThrottleGate]) — that
 * family reacts to a throttle/lock response the server *already sent*; this one is proactive, spacing
 * out login attempts so we never **reach** the response in the first place.
 *
 * **Why Yahoo/AOL are special.** Yahoo (and AOL, which shares Yahoo's mail platform) trip an automated
 * **~1-hour service lockout** after too many rapid or failed authentication attempts, and cap a
 * mailbox at **5 simultaneous IMAP connections** with the folder index truncated to **10,000
 * messages** (issue #362's documented limits). The lockout is the dangerous one: a wrong app-password
 * plus a naive retry loop (e.g. the IDLE reconnect loop, which starts at a 5-second backoff) can fire
 * several failed `LOGIN`s within the first minute and get a *real user* locked out for an hour. So for
 * these hosts the app must back off login attempts long and hard.
 *
 * **Every other provider is disabled here** ([AuthCadencePolicy.DISABLED]): Gmail, iCloud, Outlook,
 * and manually-configured servers have no comparable 1-hour auth lockout, so the proactive
 * circuit-breaker is a Yahoo/AOL-scoped no-op for them and their behaviour is unchanged (their own
 * limits are issues #361/#363/#364). Keeping the policy host-keyed — rather than refactoring shared
 * code — is what makes this change additive and safe to land alongside those siblings.
 */
data class AuthCadencePolicy(
    /** When false the whole proactive auth circuit-breaker is inert for this host (records nothing). */
    val enabled: Boolean,
    /** First-failure backoff before a retry is permitted; doubles per consecutive failure. */
    val baseBackoffMillis: Long,
    /** Ceiling on the exponential ramp, so a single wait never grows without bound. */
    val maxBackoffMillis: Long,
    /** Consecutive failures after which the circuit *opens* — retries stop for [circuitOpenMillis]. */
    val circuitOpenThreshold: Int,
    /** The long, fixed block applied once the circuit is open: "back off long and stop" (issue #362). */
    val circuitOpenMillis: Long,
    /** Documented simultaneous-connection ceiling for this provider (see [ProviderAuthPolicy]). */
    val maxConcurrentConnections: Int,
    /** Documented server-side folder-index truncation (messages) for this provider. */
    val folderIndexCap: Int,
) {
    companion object {
        /**
         * The inert policy for every host without a Yahoo-style auth lockout. Every threshold is set so
         * the gate can never block ([circuitOpenThreshold] unreachable, caps effectively unbounded), so a
         * non-Yahoo/AOL account is never gated and behaves exactly as before issue #362.
         */
        val DISABLED = AuthCadencePolicy(
            enabled = false,
            baseBackoffMillis = 0L,
            maxBackoffMillis = 0L,
            circuitOpenThreshold = Int.MAX_VALUE,
            circuitOpenMillis = 0L,
            maxConcurrentConnections = Int.MAX_VALUE,
            folderIndexCap = Int.MAX_VALUE,
        )
    }
}

/**
 * Resolves the [AuthCadencePolicy] for an IMAP host. Yahoo and AOL (one platform) get the conservative
 * lockout-avoiding policy; everything else gets [AuthCadencePolicy.DISABLED].
 */
object ProviderAuthPolicy {

    private const val MINUTE_MS = 60_000L

    /**
     * First-failure auth backoff (1 min → a 30 s floor after equal jitter, see [AuthBackoff]). The whole
     * point is that the **second** login attempt lands ≥30 s after the first: Yahoo's lockout keys on
     * *rapid* failures (attempts seconds apart, as an unguarded reconnect loop produces), and a ≥30 s
     * spacing is decisively not rapid. This floor is well under the ~1-hour lockout window it protects.
     */
    const val YAHOO_AUTH_BACKOFF_BASE_MS = MINUTE_MS

    /**
     * Ceiling on the exponential ramp (15 min). Comfortably under the ~1-hour lockout, so an account that
     * recovers (a transient auth blip clears, or the user fixes the credential) resumes far sooner than a
     * self-inflicted hour of silence, while still spacing attempts to at most a few per hour.
     */
    const val YAHOO_AUTH_BACKOFF_MAX_MS = 15 * MINUTE_MS

    /**
     * Consecutive failed logins after which the circuit opens (4). A wrong app-password does not fix
     * itself, so once we have failed this many times in a row we stop *ramping* and switch to the long
     * fixed [YAHOO_AUTH_CIRCUIT_OPEN_MS] block — "back off long and stop" — rather than keep probing and
     * risk accumulating enough failures to trip the lockout. Reached in ~3.5 min of spaced attempts.
     */
    const val YAHOO_AUTH_CIRCUIT_OPEN_THRESHOLD = 4

    /**
     * The block applied once the circuit is open (30 min). Longer than the 15-min ramp cap (we have given
     * up probing) yet still **under** the ~1-hour lockout, so recovery beats the lockout — and long
     * enough that Yahoo's short rolling failure window fully decays between our probes, holding us to at
     * most ~2 failed logins/hour once open: no rolling-window lockout heuristic reads that as rapid.
     */
    const val YAHOO_AUTH_CIRCUIT_OPEN_MS = 30 * MINUTE_MS

    /**
     * Yahoo/AOL's documented simultaneous-connection ceiling (5). LibreMail stays well under this by
     * design: connection reuse (issues #125/#357, ON by default) collapses a whole account to ~1 warm
     * IMAP socket plus at most one long-lived IDLE connection — 2 per account, not the `1 + K +
     * attachments` sockets the connect-per-operation path once opened per backfill page. Exposed as
     * config so the invariant is checkable (see the policy tests) rather than only implicit.
     */
    const val YAHOO_MAX_CONCURRENT_CONNECTIONS = 5

    /**
     * Yahoo/AOL's documented folder-index truncation (10,000 messages). The full-history backfill
     * (issue #12) already respects this for free: paging older-than-UID simply returns empty once the
     * server exposes nothing beyond the truncation point, which the backfiller treats as "folder fully
     * backfilled". Exposed as config for visibility and so a future page-cap can reference it.
     */
    const val YAHOO_FOLDER_INDEX_CAP = 10_000

    /** Shared by Yahoo and AOL — one mail platform, one set of limits. */
    private val YAHOO_AOL = AuthCadencePolicy(
        enabled = true,
        baseBackoffMillis = YAHOO_AUTH_BACKOFF_BASE_MS,
        maxBackoffMillis = YAHOO_AUTH_BACKOFF_MAX_MS,
        circuitOpenThreshold = YAHOO_AUTH_CIRCUIT_OPEN_THRESHOLD,
        circuitOpenMillis = YAHOO_AUTH_CIRCUIT_OPEN_MS,
        maxConcurrentConnections = YAHOO_MAX_CONCURRENT_CONNECTIONS,
        folderIndexCap = YAHOO_FOLDER_INDEX_CAP,
    )

    /**
     * The policy for [host] (the account's IMAP host). Yahoo/AOL — matched via the single source of truth
     * [MailProvider.forImapHost], including host aliases — get [YAHOO_AOL]; everything else, including a
     * null/blank or unknown host, gets [AuthCadencePolicy.DISABLED].
     */
    fun forHost(host: String): AuthCadencePolicy = when (MailProvider.forImapHost(host)) {
        MailProvider.YAHOO, MailProvider.AOL -> YAHOO_AOL
        else -> AuthCadencePolicy.DISABLED
    }
}
