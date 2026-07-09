// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail

import kotlin.math.min

/**
 * The pure, proactive **auth-backoff schedule** for issue #362: given a provider's [AuthCadencePolicy]
 * and how many times in a row an account has failed to authenticate ([consecutiveFailures], 1-based),
 * returns how long to block further login attempts. Side-effect- and clock-free (the caller supplies the
 * jitter draw) so the whole schedule is deterministically unit-testable; [AuthThrottleGate] owns the
 * per-account state, clock, and logging.
 *
 * Two regimes, both aimed at never tripping Yahoo/AOL's ~1-hour auth lockout:
 *  - **Ramp** (`failures < circuitOpenThreshold`): exponential in the failure count
 *    (`base * 2^(failures-1)`), clamped to [AuthCadencePolicy.maxBackoffMillis], with **equal jitter** —
 *    half the capped target as a floor, the other half spread by [random] — so the result lies in
 *    `[capped/2, capped]` and a fleet throttled at once doesn't retry in lockstep. Mirrors the equal-jitter
 *    math of issue #360's [org.libremail.data.sync.ThrottleBackoff].
 *  - **Open circuit** (`failures >= circuitOpenThreshold`): a single long, *fixed* block
 *    ([AuthCadencePolicy.circuitOpenMillis]) — "back off long and stop". Deliberately un-jittered: once we
 *    give up probing, the window is a firm floor, not something jitter can shorten.
 *
 * A [disabled][AuthCadencePolicy.enabled] policy always returns `0` (never blocks), so non-Yahoo/AOL
 * hosts are unaffected.
 */
object AuthBackoff {

    /** Caps the exponential shift so `base shl (failures-1)` can never overflow before the cap applies. */
    private const val MAX_SHIFT = 16

    /**
     * Block duration in milliseconds for the given 1-based [consecutiveFailures] under [policy], with the
     * equal-jitter draw [random] (expected in `[0.0, 1.0)`). See the class doc for the ramp vs.
     * open-circuit regimes.
     */
    fun blockMillis(policy: AuthCadencePolicy, consecutiveFailures: Int, random: Double): Long {
        require(consecutiveFailures >= 1) { "consecutiveFailures must be >= 1" }
        if (!policy.enabled) return 0L
        if (consecutiveFailures >= policy.circuitOpenThreshold) return policy.circuitOpenMillis
        val shift = min(consecutiveFailures - 1, MAX_SHIFT)
        val exponential = policy.baseBackoffMillis shl shift
        // shl can overflow to <= 0 for a pathological count; treat that as "past the cap".
        val capped = if (exponential in 1..policy.maxBackoffMillis) exponential else policy.maxBackoffMillis
        val half = capped / 2
        val jitter = (random.coerceIn(0.0, 1.0) * half).toLong()
        return half + jitter
    }
}
