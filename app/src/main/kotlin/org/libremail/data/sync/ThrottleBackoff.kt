// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import kotlin.math.min

/**
 * The pure backoff schedule for issue #360: given how many times an account has been throttled in a
 * row ([attempt], 1-based) and the [ThrottleSignal], returns how long to wait before touching that
 * account again. Exponential in the attempt, capped at a bounded maximum, with **equal jitter** so a
 * fleet of clients throttled at once don't retry in lockstep and re-trip the limit.
 *
 * Kept side-effect-free and clock-free (the caller supplies the jitter draw) so the whole schedule is
 * deterministically unit-testable; [AccountThrottleGate] owns the per-account state, clock, and logging.
 */
object ThrottleBackoff {

    /** First-attempt wait for a rate limit (30s); doubles per repeat up to [RATE_LIMIT_MAX_MS]. */
    const val RATE_LIMIT_BASE_MS = 30_000L

    /** Ceiling for a rate-limit backoff (15 min) — long enough to clear a clamp, short enough to recover. */
    const val RATE_LIMIT_MAX_MS = 15 * 60_000L

    /**
     * First-attempt wait for a lockout (1h) — sized to Yahoo's documented ~1-hour auth lock, the case
     * that motivated the circuit-breaker lever in issue #360.
     */
    const val LOCKOUT_BASE_MS = 60 * 60_000L

    /** Ceiling for a lockout backoff (4h) — a repeatedly re-locked account waits out ever longer windows. */
    const val LOCKOUT_MAX_MS = 4 * 60 * 60_000L

    /** Caps the exponential shift so `base shl (attempt-1)` can never overflow before the min-cap applies. */
    private const val MAX_SHIFT = 16

    /**
     * Backoff in milliseconds for the given 1-based [attempt] and [signal]. The uncapped target is
     * `base * 2^(attempt-1)`, clamped to the kind's maximum; **equal jitter** then keeps half of that
     * as a floor and spreads the other half by [random] (expected in `[0.0, 1.0)`), so the result lies
     * in `[capped/2, capped]`. Finally the provider's own [ThrottleSignal.retryAfterMillis], when
     * present, is honored as a lower bound — we never wait less than a server explicitly asked for.
     */
    fun delayMillis(attempt: Int, signal: ThrottleSignal, random: Double): Long {
        require(attempt >= 1) { "attempt must be >= 1" }
        val base: Long
        val cap: Long
        when (signal.kind) {
            ThrottleKind.RATE_LIMIT -> {
                base = RATE_LIMIT_BASE_MS
                cap = RATE_LIMIT_MAX_MS
            }
            ThrottleKind.LOCKOUT -> {
                base = LOCKOUT_BASE_MS
                cap = LOCKOUT_MAX_MS
            }
        }
        val shift = min(attempt - 1, MAX_SHIFT)
        val exponential = base shl shift
        // shl can overflow to <= 0 for a pathological attempt; treat that as "past the cap".
        val capped = if (exponential in 1..cap) exponential else cap
        val half = capped / 2
        val jitter = (random.coerceIn(0.0, 1.0) * half).toLong()
        val backoff = half + jitter
        val floor = signal.retryAfterMillis ?: 0L
        return maxOf(backoff, floor)
    }
}
