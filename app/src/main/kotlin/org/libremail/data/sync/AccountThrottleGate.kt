// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import org.libremail.reporting.AppLog
import org.libremail.reporting.accountLogRef
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-account reactive throttle state for issue #360: the single, shared place that turns a classified
 * [ThrottleSignal] (from [ThrottleClassifier]) into an exponentially-growing, jittered backoff window
 * (via [ThrottleBackoff]) and remembers, per account, when that window elapses.
 *
 * **Graceful degradation, not failing hard.** Background activity (notably the full-history backfill,
 * [MailBackfiller]) consults [remainingBackoffMillis] / [isThrottled] before touching an account and
 * *skips* one that is still cooling down, resuming automatically once the window passes — instead of
 * re-hitting a provider that just rate-limited or locked us (which the on-device perf drilldown proved
 * makes throttling worse, `docs/perf/issue-125-*`).
 *
 * **Per-account isolation.** State is keyed by account id, so one throttled account never stalls the
 * others; each escalates and recovers on its own.
 *
 * **Interactive priority.** Interactive/foreground sync feeds this gate ([onThrottle]) so background
 * work backs off, but is itself never blocked by it — opening a message is never queued behind a
 * backfill backoff.
 *
 * State lives only in-process (a `@Singleton`); a process restart resets it, which is fine — WorkManager
 * job backoff covers the cross-process case and a fresh process simply re-probes. Every log line is
 * PII-free: [accountLogRef] for the account, and durations/counts only.
 */
@Singleton
class AccountThrottleGate internal constructor(private val nowMillis: () -> Long, private val random: () -> Double) {
    /** Production wiring: the real wall clock and a per-thread RNG for the jitter draw. */
    @Inject
    constructor() : this(nowMillis = System::currentTimeMillis, random = { ThreadLocalRandom.current().nextDouble() })

    /** One account's live backoff: how many consecutive throttles, until when, and the last computed wait. */
    private data class State(val attempt: Int, val throttledUntilMillis: Long, val lastBackoffMillis: Long)

    private val states = ConcurrentHashMap<String, State>()

    /**
     * Records a throttle for [accountId] and returns the resulting backoff in ms. Escalates the
     * consecutive-attempt count (so repeats back off exponentially) and extends the account's cooldown
     * window to `now + backoff`. Atomic per account. Logs a PII-free breadcrumb (kind, attempt, backoff).
     */
    fun onThrottle(accountId: String, signal: ThrottleSignal): Long {
        val now = nowMillis()
        val updated = states.compute(accountId) { _, previous ->
            val attempt = (previous?.attempt ?: 0) + 1
            val backoff = ThrottleBackoff.delayMillis(attempt, signal, random())
            State(attempt = attempt, throttledUntilMillis = now + backoff, lastBackoffMillis = backoff)
        }!!
        AppLog.w(
            TAG,
            "throttled ${accountLogRef(accountId)} kind=${signal.kind} attempt=${updated.attempt} " +
                "backoff=${updated.lastBackoffMillis}ms",
        )
        return updated.lastBackoffMillis
    }

    /**
     * Clears any backoff for [accountId] after a successful operation, so a recovered account resumes at
     * full speed with the attempt count reset. A no-op (and silent) when the account was not throttled,
     * so callers can invoke it on every success without log spam.
     */
    fun onSuccess(accountId: String) {
        val previous = states.remove(accountId) ?: return
        AppLog.i(TAG, "throttle cleared ${accountLogRef(accountId)} after ${previous.attempt} attempt(s)")
    }

    /**
     * Milliseconds until [accountId]'s backoff window elapses, or 0 when it is not throttled (or the
     * window already passed). A passed window keeps its attempt count until the next [onSuccess], so a
     * re-throttle before recovery escalates rather than restarting from the base delay.
     */
    fun remainingBackoffMillis(accountId: String): Long {
        val state = states[accountId] ?: return 0L
        return (state.throttledUntilMillis - nowMillis()).coerceAtLeast(0L)
    }

    /** True while [accountId] is inside its backoff window and background work should skip it. */
    fun isThrottled(accountId: String): Boolean = remainingBackoffMillis(accountId) > 0L

    private companion object {
        const val TAG = "ThrottleGate"
    }
}
