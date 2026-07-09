// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.domain.model.ImapConnectionParams
import org.libremail.domain.model.MailProvider
import org.libremail.domain.model.MailSecurity

/**
 * On-device proof of issue #362's proactive auth circuit-breaker on the REAL Android runtime (not the
 * JVM stubs), across the CI API matrix. A real [AuthThrottleGate] with an always-Yahoo policy and a
 * manual clock — so it is deterministic and never real-sleeps — must, for a Yahoo/AOL account: block a
 * login after an auth failure, escalate rapid failures without reaching the ~1-hour lockout window, open
 * a long fixed circuit past the threshold, isolate accounts, and clear on success; and it must be a
 * total no-op for a non-gated host.
 *
 * Deliberately mock-free (no `mockk`, no framework `Context`): the gate's only inputs are plain lambdas
 * and value objects, so this exercises the genuine state machine on the device and dodges the
 * mockk-on-framework-types landmines. The JVM [AuthThrottleGateTest] covers the same contract under
 * coroutines-test virtual time; this proves it survives the real dispatcher and API levels.
 */
@RunWith(AndroidJUnit4::class)
class AuthThrottleGateInstrumentedTest {

    private var now = 0L
    private val yahoo = ProviderAuthPolicy.forHost(MailProvider.YAHOO.createAccount("x@yahoo.com").imap.host)

    private fun gate(policyForHost: (String) -> AuthCadencePolicy = { yahoo }) =
        AuthThrottleGate(nowMillis = { now }, random = { 0.0 }, policyForHost = policyForHost)

    private fun params(user: String = "user@example.org", host: String = "imap.mail.yahoo.com") =
        ImapConnectionParams(host, PORT, MailSecurity.SSL_TLS, user, secret = "secret", useXoauth2 = false)

    @Test
    fun anAuthFailureBlocksTheAccountAndEscalatesWithoutReachingTheLockout() {
        val gate = gate()
        val p = params()

        val first = gate.onAuthFailure(p)
        assertTrue("a failure blocks the account", gate.isAuthBlocked(p))
        assertTrue("the first block is positive", first > 0L)

        val second = gate.onAuthFailure(p)
        assertTrue("a consecutive failure backs off longer", second > first)

        // Never as long as the ~1-hour lockout the backoff exists to avoid.
        repeat(RAPID_FAILURES) { assertTrue(gate.onAuthFailure(p) < ONE_HOUR_MS) }
    }

    @Test
    fun theCircuitLatchesPastTheThresholdAndNeverSelfClears() {
        val gate = gate()
        val p = params()

        repeat(yahoo.circuitOpenThreshold) { gate.onAuthFailure(p) }
        assertTrue("reaching the threshold latches the circuit", gate.isAuthLatched(p))
        assertTrue(gate.isAuthBlocked(p))

        // Issue #362 fail-loud stop: the old self-clearing open-circuit window is gone — time never
        // unblocks a latch, only a fresh account re-add does.
        now += yahoo.circuitOpenMillis * LATCH_ELAPSE_FACTOR
        assertTrue("a latched circuit never self-clears with time", gate.isAuthBlocked(p))

        // A success does not clear a latch either (defensive: no login is attempted while latched).
        gate.onAuthSuccess(p)
        assertTrue("a success must not clear a latch", gate.isAuthLatched(p))

        // Only a re-add drops it, letting a fresh credential log in again.
        gate.onAccountReadded(p)
        assertFalse("a re-add clears the latch", gate.isAuthLatched(p))
        assertFalse(gate.isAuthBlocked(p))
    }

    @Test
    fun accountsAreIsolatedAndSuccessClearsTheBackoff() {
        val gate = gate()
        val blocked = params(user = "blocked@example.org")
        val healthy = params(user = "healthy@example.org")

        gate.onAuthFailure(blocked)
        assertTrue(gate.isAuthBlocked(blocked))
        assertFalse("one blocked account never stalls another", gate.isAuthBlocked(healthy))

        gate.onAuthSuccess(blocked)
        assertFalse("a successful login clears the backoff", gate.isAuthBlocked(blocked))
    }

    @Test
    fun theWindowClearsOnceItElapses() {
        val gate = gate()
        val p = params()

        val block = gate.onAuthFailure(p)
        now += block
        assertFalse("the account may retry once the window elapses", gate.isAuthBlocked(p))
    }

    @Test
    fun aNonGatedHostIsNeverBlocked() {
        val gate = gate(policyForHost = ProviderAuthPolicy::forHost)
        val gmail = params(host = "imap.gmail.com")

        assertEquals(0L, gate.onAuthFailure(gmail))
        assertFalse(gate.isAuthBlocked(gmail))
    }

    private companion object {
        const val PORT = 993
        const val RAPID_FAILURES = 10
        const val ONE_HOUR_MS = 60 * 60_000L

        /** How many old open-circuit windows to fast-forward to prove a latch never self-clears (#362). */
        const val LATCH_ELAPSE_FACTOR = 10
    }
}
