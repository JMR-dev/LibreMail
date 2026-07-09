// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.data.sync.AccountThrottleGate
import org.libremail.data.sync.ThrottleBackoff
import org.libremail.data.sync.ThrottleKind
import org.libremail.data.sync.ThrottleSignal
import org.libremail.domain.model.ImapConnectionParams
import org.libremail.domain.model.MailProvider
import org.libremail.domain.model.MailSecurity
import org.libremail.reporting.AppLog
import org.libremail.reporting.RingLogBuffer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [AuthThrottleGate] (issue #362) must, for a Yahoo/AOL account: block logins after an auth failure,
 * escalate rapid consecutive failures without ever reaching the ~1-hour lockout window, open a long
 * fixed circuit past the threshold, isolate accounts, reset on success, expose an accurate remaining
 * window (proven against coroutines-test virtual time), and log only PII-free breadcrumbs. It must be a
 * total no-op for a non-gated host, and it must *compose* with issue #360's reactive gate rather than
 * fight it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthThrottleGateTest {

    private val logBuffer = RingLogBuffer()

    /** A manual virtual clock for the non-timing tests; [gate] reads it live, so tests advance it by hand. */
    private var now = 0L

    /** The real Yahoo policy — the production values under test. */
    private val yahoo = ProviderAuthPolicy.forHost(MailProvider.YAHOO.createAccount("x@yahoo.com").imap.host)

    /** random = 0.0 makes the equal-jitter draw deterministic (always the lower bound of the ramp). */
    private fun gate(random: () -> Double = { 0.0 }, policyForHost: (String) -> AuthCadencePolicy = { yahoo }) =
        AuthThrottleGate(nowMillis = { now }, random = random, policyForHost = policyForHost)

    private fun params(user: String = "user@example.org", host: String = "imap.mail.yahoo.com") =
        ImapConnectionParams(host, PORT, MailSecurity.SSL_TLS, user, secret = "secret", useXoauth2 = false)

    @Before
    fun setUp() {
        // AppLog forwards to android.util.Log, a throwing no-op stub under plain JVM unit tests. Fully
        // qualified so this file never imports android.util.Log (a detekt-forbidden import, epic #324).
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        AppLog.install(logBuffer)
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `onAuthFailure blocks the account and returns the backoff`() {
        val gate = gate()
        val p = params()

        val backoff = gate.onAuthFailure(p)

        assertTrue(backoff > 0L)
        assertTrue(gate.isAuthBlocked(p))
        assertEquals(backoff, gate.remainingAuthBlockMillis(p))
    }

    @Test
    fun `rapid consecutive auth failures escalate the block within the ramp`() {
        val gate = gate()
        val p = params()

        val first = gate.onAuthFailure(p)
        val second = gate.onAuthFailure(p)
        val third = gate.onAuthFailure(p)

        assertTrue(second > first, "a consecutive failure must back off longer ($second !> $first)")
        assertTrue(third > second, "and longer again ($third !> $second)")
    }

    @Test
    fun `rapid auth failures never reach the one-hour lockout window`() {
        val gate = gate()
        val p = params()

        // Hammer a wrong credential as fast as an unguarded loop would: every resulting block must stay
        // well under the ~1-hour lockout it exists to prevent — that is the whole point of issue #362.
        repeat(RAPID_FAILURES) {
            val block = gate.onAuthFailure(p)
            assertTrue(block < ThrottleBackoff.LOCKOUT_BASE_MS, "block $block reached the 1-hour lockout window")
        }
    }

    @Test
    fun `the circuit opens after the threshold to a long fixed window and stays open`() {
        val gate = gate()
        val p = params()

        var lastBlock = 0L
        repeat(yahoo.circuitOpenThreshold) { lastBlock = gate.onAuthFailure(p) }
        assertEquals(yahoo.circuitOpenMillis, lastBlock, "the threshold failure opens the fixed circuit window")

        // A further failure stays open at the same fixed window (no runaway escalation).
        assertEquals(yahoo.circuitOpenMillis, gate.onAuthFailure(p))
        assertTrue(gate.isAuthBlocked(p))
    }

    @Test
    fun `onAuthSuccess clears the backoff and resets the failure count`() {
        val gate = gate()
        val p = params()

        val first = gate.onAuthFailure(p)
        gate.onAuthFailure(p) // escalate to failure 2
        gate.onAuthSuccess(p)

        assertFalse(gate.isAuthBlocked(p))
        // A fresh failure after recovery starts back at the base (failure 1) delay.
        assertEquals(first, gate.onAuthFailure(p))
    }

    @Test
    fun `a blocked account never stalls another`() {
        val gate = gate()
        val blocked = params(user = "blocked@example.org")
        val healthy = params(user = "healthy@example.org")

        gate.onAuthFailure(blocked)

        assertTrue(gate.isAuthBlocked(blocked))
        assertFalse(gate.isAuthBlocked(healthy))
        assertEquals(0L, gate.remainingAuthBlockMillis(healthy))
    }

    @Test
    fun `an elapsed window stops blocking but still escalates a re-failure`() {
        val gate = gate()
        val p = params()

        val first = gate.onAuthFailure(p)
        now += first // the window elapses
        assertFalse(gate.isAuthBlocked(p), "the account may attempt a login once its window passes")

        // Re-failing before any success keeps the failure count — it escalates, not restarts.
        val next = gate.onAuthFailure(p)
        assertTrue(next > first)
    }

    @Test
    fun `the block window clears exactly when the backoff elapses`() = runTest {
        val gate = AuthThrottleGate(
            nowMillis = { testScheduler.currentTime },
            random = { 0.0 },
            policyForHost = { yahoo },
        )
        val p = params()

        val backoff = gate.onAuthFailure(p)
        assertTrue(gate.isAuthBlocked(p))

        advanceTimeBy(backoff - 1)
        assertTrue(gate.isAuthBlocked(p), "still blocked just before the window elapses")

        advanceTimeBy(1)
        assertFalse(gate.isAuthBlocked(p), "cleared the instant the backoff elapses")
        assertEquals(0L, gate.remainingAuthBlockMillis(p))
    }

    @Test
    fun `a non-gated host is never blocked`() {
        // Gmail has no 1-hour auth lockout, so its real policy is DISABLED: failures record nothing.
        val gate = gate(policyForHost = ProviderAuthPolicy::forHost)
        val gmail = params(host = "imap.gmail.com")

        assertEquals(0L, gate.onAuthFailure(gmail))
        assertFalse(gate.isAuthBlocked(gmail))
        assertTrue(logBuffer.snapshot().isEmpty(), "an ungated host logs no auth-backoff breadcrumb")
    }

    @Test
    fun `auth backoff and recovery log PII-free breadcrumbs`() {
        val gate = gate()
        val p = params(user = "secret.user@example.org", host = "imap.mail.yahoo.com")

        gate.onAuthFailure(p)
        gate.onAuthSuccess(p)

        val messages = logBuffer.snapshot().map { it.message }
        assertTrue(messages.any { it.startsWith("auth backoff acct:") && it.contains("failures=1") })
        assertTrue(messages.any { it.startsWith("auth recovered acct:") })
        messages.forEach {
            assertFalse(it.contains("secret.user@example.org"), it)
            assertFalse(it.contains("yahoo"), it)
        }
    }

    @Test
    fun `onAuthSuccess on a healthy account is silent`() {
        gate().onAuthSuccess(params())

        assertTrue(logBuffer.snapshot().isEmpty())
    }

    @Test
    fun `composes with the reactive throttle gate without interference (issue #360)`() {
        val authGate = gate()
        val throttleGate = AccountThrottleGate(nowMillis = { now }, random = { 0.0 })
        val p = params()

        // The same account can be BOTH proactively auth-backing-off and reactively throttled; the two
        // gates keep independent state and neither clears the other.
        val authBlock = authGate.onAuthFailure(p)
        val lockout = throttleGate.onThrottle("acct", ThrottleSignal(ThrottleKind.LOCKOUT))
        assertTrue(authGate.isAuthBlocked(p))
        assertTrue(throttleGate.isThrottled("acct"))
        authGate.onAuthSuccess(p)
        assertFalse(authGate.isAuthBlocked(p))
        assertTrue(throttleGate.isThrottled("acct"), "clearing the auth gate must not clear the reactive one")

        // The proactive auth backoff is always shorter than the reactive lockout it prevents reaching.
        assertTrue(authBlock < lockout, "proactive auth backoff ($authBlock) must be shorter than lockout ($lockout)")
    }

    private companion object {
        const val PORT = 993
        const val RAPID_FAILURES = 10
    }
}
