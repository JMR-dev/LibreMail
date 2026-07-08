// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The pure backoff schedule (issue #360): exponential in the consecutive-attempt count, capped at a
 * bounded maximum, spread by equal jitter into `[capped/2, capped]`, and never shorter than a
 * provider-supplied `Retry-After`.
 */
class ThrottleBackoffTest {

    private val rateLimit = ThrottleSignal(ThrottleKind.RATE_LIMIT)
    private val lockout = ThrottleSignal(ThrottleKind.LOCKOUT)

    /** random = 0.0 selects the lower jitter bound (capped/2); random = 1.0 selects the upper (capped). */
    private fun low(attempt: Int, signal: ThrottleSignal) = ThrottleBackoff.delayMillis(attempt, signal, random = 0.0)
    private fun high(attempt: Int, signal: ThrottleSignal) = ThrottleBackoff.delayMillis(attempt, signal, random = 1.0)

    @Test
    fun `rate-limit backoff doubles per attempt at the lower jitter bound`() {
        assertEquals(ThrottleBackoff.RATE_LIMIT_BASE_MS / 2, low(1, rateLimit))
        assertEquals(ThrottleBackoff.RATE_LIMIT_BASE_MS, low(2, rateLimit))
        assertEquals(ThrottleBackoff.RATE_LIMIT_BASE_MS * 2, low(3, rateLimit))
    }

    @Test
    fun `the upper jitter bound of attempt 1 is the base delay`() {
        assertEquals(ThrottleBackoff.RATE_LIMIT_BASE_MS, high(1, rateLimit))
    }

    @Test
    fun `jitter keeps every draw within the exponential half-window`() {
        // Attempt 2's capped target is 2*base; equal jitter must land in [base, 2*base] for any draw.
        val lower = ThrottleBackoff.RATE_LIMIT_BASE_MS
        val upper = ThrottleBackoff.RATE_LIMIT_BASE_MS * 2
        for (thousandths in 0..1000) {
            val delay = ThrottleBackoff.delayMillis(attempt = 2, rateLimit, random = thousandths / 1000.0)
            assertTrue(delay in lower..upper, "draw $thousandths gave $delay, outside [$lower,$upper]")
        }
    }

    @Test
    fun `a large attempt is capped at the rate-limit maximum`() {
        // 2^19 * base overflows the cap many times over; the max must hold for both jitter bounds.
        assertEquals(ThrottleBackoff.RATE_LIMIT_MAX_MS / 2, low(20, rateLimit))
        assertEquals(ThrottleBackoff.RATE_LIMIT_MAX_MS, high(20, rateLimit))
    }

    @Test
    fun `a lockout starts at a long window and caps higher than a rate limit`() {
        assertEquals(ThrottleBackoff.LOCKOUT_BASE_MS / 2, low(1, lockout))
        assertEquals(ThrottleBackoff.LOCKOUT_BASE_MS, high(1, lockout))
        assertEquals(ThrottleBackoff.LOCKOUT_MAX_MS, high(20, lockout))
        assertTrue(ThrottleBackoff.LOCKOUT_MAX_MS > ThrottleBackoff.RATE_LIMIT_MAX_MS)
    }

    @Test
    fun `a retry-after longer than the computed delay is honored as a floor`() {
        val retryAfter = ThrottleBackoff.RATE_LIMIT_MAX_MS * 4
        val signal = ThrottleSignal(ThrottleKind.RATE_LIMIT, retryAfterMillis = retryAfter)
        assertEquals(retryAfter, ThrottleBackoff.delayMillis(attempt = 1, signal, random = 1.0))
    }

    @Test
    fun `a retry-after shorter than the computed delay does not shorten the backoff`() {
        val signal = ThrottleSignal(ThrottleKind.RATE_LIMIT, retryAfterMillis = 1L)
        assertEquals(ThrottleBackoff.RATE_LIMIT_BASE_MS / 2, ThrottleBackoff.delayMillis(1, signal, random = 0.0))
    }

    @Test
    fun `attempt below 1 is rejected`() {
        assertFailsWith<IllegalArgumentException> { ThrottleBackoff.delayMillis(0, rateLimit, random = 0.0) }
    }
}
