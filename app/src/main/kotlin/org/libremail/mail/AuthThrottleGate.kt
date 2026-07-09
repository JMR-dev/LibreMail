// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail

import jakarta.mail.MessagingException
import org.libremail.domain.model.ImapConnectionParams
import org.libremail.reporting.AppLog
import org.libremail.reporting.accountLogRef
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thrown by [ImapClient] instead of attempting a `LOGIN` while an account is inside its proactive
 * auth-backoff window (issue #362): the login is *skipped*, not merely retried, so a Yahoo/AOL account
 * never accumulates the rapid failed logins that trip the ~1-hour lockout. Extends [MessagingException]
 * so existing IMAP error handling catches it, and carries **no PII** — only the remaining wait. Its
 * message deliberately avoids any throttle/lock wording so [org.libremail.data.sync.ThrottleClassifier]
 * does not misread it as a *reactive* throttle signal, and [ImapConnectionCache] does not treat it as a
 * connection drop (its cause is null), so a reused connection is never needlessly rebuilt on it.
 */
class AuthBackoffException(remainingMillis: Long) :
    MessagingException("IMAP login paused: backing off ${remainingMillis}ms to protect the account")

/**
 * Per-account **proactive auth circuit-breaker** for issue #362 — the enforcing state machine that turns
 * repeated authentication failures into an [AuthBackoff]-scheduled block, so LibreMail never hammers
 * Yahoo/AOL's login endpoint into its automated **~1-hour lockout**.
 *
 * This is the proactive counterpart to issue #360's reactive [org.libremail.data.sync.AccountThrottleGate]:
 * that gate reacts to a throttle/lock response the server *already sent*; this one prevents us from ever
 * eliciting one. [ImapClient] consults [remainingAuthBlockMillis] before every real `LOGIN` (connect-per-op,
 * the reuse cache's connect/reconnect, and the long-lived IDLE connection) and throws [AuthBackoffException]
 * instead of connecting while blocked; it records the outcome via [onAuthFailure] / [onAuthSuccess]. The
 * full-history backfill ([org.libremail.data.sync.MailBackfiller]) additionally *skips* an auth-blocked
 * account, exactly as it skips a reactively-throttled one (#360) — so the two gates compose and the
 * [org.libremail.data.sync.BackfillPacer] (#356) never burns cooldowns spinning on a blocked login.
 *
 * **Only Yahoo/AOL are gated.** State is created solely when [ProviderAuthPolicy.forHost] returns an
 * enabled policy, so every other provider (Gmail/iCloud/Outlook, issues #361/#363/#364, and manual
 * servers) is a no-op here and unchanged.
 *
 * **Per-account isolation & PII-free.** State is keyed by the account's connection identity
 * (`host|port|username`), so one blocked account never stalls another, and every log line uses
 * [accountLogRef] over that key — a salted-looking hash, never the address or host.
 *
 * **Fail-loud latch (issue #362).** Past [AuthCadencePolicy.circuitOpenThreshold] consecutive failures the
 * circuit **latches**: a wrong app-password does not fix itself, so instead of a self-clearing window we
 * stop retrying *entirely* and hold the account blocked forever. The latch is surfaced to the user as a
 * persisted account error ("remove and re-add") by
 * [org.libremail.data.sync.markAccountErroredIfLatched], and is cleared only by a fresh account re-add
 * ([onAccountReadded]) — never by time or a stray success.
 *
 * The in-memory latch itself lives only in-process (`@Singleton`); the durable stop is the persisted
 * account error, which the sync/backfill loops honour across restarts, so a process restart does not
 * quietly resume probing a latched account. Below the threshold, the in-memory ramp is transient — a
 * restart there simply re-probes (spaced from the previous run), and any real re-failure re-arms it.
 */
@Singleton
class AuthThrottleGate internal constructor(
    private val nowMillis: () -> Long,
    private val random: () -> Double,
    private val policyForHost: (String) -> AuthCadencePolicy,
) {
    /** Production wiring: the real wall clock, a per-thread RNG for jitter, and the host-keyed policy. */
    @Inject
    constructor() : this(
        nowMillis = System::currentTimeMillis,
        random = { ThreadLocalRandom.current().nextDouble() },
        policyForHost = ProviderAuthPolicy::forHost,
    )

    /**
     * One account's auth state: consecutive failures, until when logins are blocked, the last computed
     * wait, and whether the circuit has **latched** — a permanent fail-loud stop past the threshold that
     * clears only on a fresh account re-add (issue #362), never by time or a success.
     */
    private data class State(
        val failures: Int,
        val blockedUntilMillis: Long,
        val lastBlockMillis: Long,
        val latched: Boolean,
    )

    private val states = ConcurrentHashMap<String, State>()

    /**
     * Records a failed authentication for [params]'s account and returns the resulting block in ms (0 when
     * the host has no auth-lockout risk, so the call is a no-op). Below the threshold it escalates the
     * consecutive-failure count so repeats back off exponentially and stamps the account blocked until
     * `now + block`. At the threshold the circuit **latches**: a permanent block (fail-loud stop) that no
     * further failure escalates and no success or elapsed time clears — only [onAccountReadded] does
     * (issue #362). Atomic per account. Logs a PII-free breadcrumb (the latch transition once, or the
     * failure count + block while ramping).
     */
    fun onAuthFailure(params: ImapConnectionParams): Long {
        val policy = policyForHost(params.host)
        if (!policy.enabled) return 0L
        val now = nowMillis()
        var alreadyLatched = false
        val updated = states.compute(key(params)) { _, previous ->
            // A latched circuit is a permanent stop: further failures neither escalate nor re-arm it (and
            // the failure count stays frozen), so we never spam the log or drift the state once we give up.
            if (previous?.latched == true) {
                alreadyLatched = true
                return@compute previous
            }
            val failures = (previous?.failures ?: 0) + 1
            val block = AuthBackoff.blockMillis(policy, failures, random())
            val latched = failures >= policy.circuitOpenThreshold
            State(
                failures = failures,
                // Latched: block "forever" (Long.MAX_VALUE never elapses) so every subsequent login is
                // skipped until a re-add — the fail-loud stop that replaced the old self-clearing window.
                blockedUntilMillis = if (latched) Long.MAX_VALUE else now + block,
                lastBlockMillis = block,
                latched = latched,
            )
        }!!
        when {
            alreadyLatched -> Unit // logged once when it first latched; stay silent thereafter
            updated.latched -> AppLog.w(
                TAG,
                "auth circuit latched ${logRef(params)} after ${updated.failures} failure(s): " +
                    "retries stopped, account will be errored",
            )
            else -> AppLog.w(
                TAG,
                "auth backoff ${logRef(params)} failures=${updated.failures} block=${updated.lastBlockMillis}ms",
            )
        }
        return updated.lastBlockMillis
    }

    /**
     * Clears a *ramping* (not yet latched) auth-backoff for [params]'s account after a successful login, so
     * a recovered account resumes at full speed with the failure count reset. A **latched** circuit is
     * deliberately left intact — it is cleared only by a fresh account re-add ([onAccountReadded]), never by
     * a success (issue #362); while latched no login is even attempted, so this is a defensive guard. Silent
     * no-op when the account was not blocked (or the host is not gated), so [ImapClient] can call it on every
     * successful connect.
     */
    fun onAuthSuccess(params: ImapConnectionParams) {
        var cleared: State? = null
        states.compute(key(params)) { _, current ->
            when {
                current == null -> null
                current.latched -> current // a latched circuit clears only on re-add, never on success
                else -> {
                    cleared = current
                    null
                }
            }
        }
        cleared?.let { AppLog.i(TAG, "auth recovered ${logRef(params)} after ${it.failures} failure(s)") }
    }

    /**
     * True once [params]'s account has permanently **latched** its auth circuit (issue #362) — the
     * threshold of consecutive Yahoo/AOL auth failures reached — so it must be surfaced to the user as
     * errored and no login retried until a fresh account re-add.
     */
    fun isAuthLatched(params: ImapConnectionParams): Boolean = states[key(params)]?.latched == true

    /**
     * Drops ALL auth state for [params]'s account — including a permanently latched circuit — because the
     * user re-added the account with fresh credentials (issue #362). This is the single path that escapes a
     * latch: the next login is then attempted clean. Called from
     * [org.libremail.data.repository.AccountRepositoryImpl] on add (before the connection test), paired with
     * clearing the persisted account error, so a re-add fully resumes sync.
     */
    fun onAccountReadded(params: ImapConnectionParams) {
        if (states.remove(key(params)) != null) {
            AppLog.i(TAG, "auth state reset ${logRef(params)}: account re-added, retries resume")
        }
    }

    /**
     * Milliseconds until [params]'s account may attempt a login again, or 0 when it is not blocked (or the
     * window already elapsed). A passed window keeps its failure count until the next [onAuthSuccess], so a
     * re-failure before recovery escalates rather than restarting from the base delay.
     */
    fun remainingAuthBlockMillis(params: ImapConnectionParams): Long {
        val state = states[key(params)] ?: return 0L
        return (state.blockedUntilMillis - nowMillis()).coerceAtLeast(0L)
    }

    /** True while [params]'s account is inside its auth-backoff window and a login must be skipped. */
    fun isAuthBlocked(params: ImapConnectionParams): Boolean = remainingAuthBlockMillis(params) > 0L

    /** PII-free, stable reference for [params]'s account — a hash of the connection identity, never it. */
    fun logRef(params: ImapConnectionParams): String = accountLogRef(key(params))

    /** Connection identity keying the state: everything that pins a distinct authenticated login. */
    private fun key(params: ImapConnectionParams): String = "${params.host}|${params.port}|${params.username}"

    private companion object {
        const val TAG = "AuthThrottleGate"
    }
}
