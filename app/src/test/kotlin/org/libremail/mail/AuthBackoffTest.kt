// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail

import org.junit.Test
import org.libremail.domain.model.MailProvider
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The pure proactive auth-backoff schedule (issue #362): an exponential *ramp* (equal jitter, capped)
 * up to the circuit-open threshold, then a long *fixed* open-circuit window — "back off long and stop".
 * A disabled policy never blocks. Deterministic (the caller supplies the jitter draw), so no clock or
 * randomness leaks into the assertions.
 */
class AuthBackoffTest {

    /** A small controlled policy so the ramp/cap arithmetic reads clearly (base 1s, cap 8s, circuit 60s). */
    private val policy = AuthCadencePolicy(
        enabled = true,
        baseBackoffMillis = 1_000L,
        maxBackoffMillis = 8_000L,
        circuitOpenThreshold = 4,
        circuitOpenMillis = 60_000L,
        maxConcurrentConnections = 5,
        folderIndexCap = 10_000,
    )

    /** random = 0.0 selects the lower jitter bound (capped/2); random = 1.0 selects the upper (capped). */
    private fun low(failures: Int, p: AuthCadencePolicy = policy) = AuthBackoff.blockMillis(p, failures, random = 0.0)
    private fun high(failures: Int, p: AuthCadencePolicy = policy) = AuthBackoff.blockMillis(p, failures, random = 1.0)

    @Test
    fun `the ramp doubles per failure at the lower jitter bound`() {
        assertEquals(policy.baseBackoffMillis / 2, low(1)) // 500ms
        assertEquals(policy.baseBackoffMillis, low(2)) // 1000ms
        assertEquals(policy.baseBackoffMillis * 2, low(3)) // 2000ms
    }

    @Test
    fun `the upper jitter bound of the first failure is the base delay`() {
        assertEquals(policy.baseBackoffMillis, high(1))
    }

    @Test
    fun `jitter keeps every ramp draw within the exponential half-window`() {
        // Failure 2's capped target is 2*base; equal jitter must land in [base, 2*base] for any draw.
        val lower = policy.baseBackoffMillis
        val upper = policy.baseBackoffMillis * 2
        for (thousandths in 0..1000) {
            val delay = AuthBackoff.blockMillis(policy, consecutiveFailures = 2, random = thousandths / 1000.0)
            assertTrue(delay in lower..upper, "draw $thousandths gave $delay, outside [$lower,$upper]")
        }
    }

    @Test
    fun `the ramp is capped at the policy maximum`() {
        // Raise the threshold so the ramp actually reaches the cap: failure 5 wants 16*base > cap.
        val ramped = policy.copy(circuitOpenThreshold = 100)
        assertEquals(ramped.maxBackoffMillis / 2, low(5, ramped))
        assertEquals(ramped.maxBackoffMillis, high(5, ramped))
    }

    @Test
    fun `at the threshold the circuit opens to a long fixed window with no jitter`() {
        // Failure 4 (== threshold) and beyond return the fixed open-circuit window for any jitter draw.
        assertEquals(policy.circuitOpenMillis, low(4))
        assertEquals(policy.circuitOpenMillis, high(4))
        assertEquals(policy.circuitOpenMillis, low(9))
        assertTrue(
            policy.circuitOpenMillis > policy.maxBackoffMillis,
            "the open-circuit window is longer than the ramp cap — we have given up probing",
        )
    }

    @Test
    fun `a disabled policy never blocks`() {
        assertEquals(0L, high(1, AuthCadencePolicy.DISABLED))
        assertEquals(0L, high(50, AuthCadencePolicy.DISABLED))
    }

    @Test
    fun `a failure count below one is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            AuthBackoff.blockMillis(policy, consecutiveFailures = 0, random = 0.0)
        }
    }

    @Test
    fun `the real yahoo schedule keeps every wait under the one-hour lockout`() {
        // The heart of issue #362: however many times in a row auth fails, no single block reaches the
        // ~1-hour lockout window — the backoff protects the account without ever matching the punishment.
        val yahooHost = MailProvider.YAHOO.createAccount("x@yahoo.com").imap.host
        val yahoo = ProviderAuthPolicy.forHost(yahooHost)
        for (failures in 1..12) {
            assertTrue(low(failures, yahoo) < ONE_HOUR_MS, "failure $failures low bound reached the lockout window")
            assertTrue(high(failures, yahoo) < ONE_HOUR_MS, "failure $failures high bound reached the lockout window")
        }
    }

    private companion object {
        const val ONE_HOUR_MS = 60 * 60_000L
    }
}
