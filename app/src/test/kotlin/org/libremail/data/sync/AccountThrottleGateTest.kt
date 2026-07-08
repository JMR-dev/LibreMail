// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.reporting.AppLog
import org.libremail.reporting.RingLogBuffer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [AccountThrottleGate] must escalate a throttled account's backoff, isolate accounts from one
 * another, reset on success, expose an accurate remaining window (proven against coroutines-test
 * virtual time), and log only PII-free breadcrumbs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountThrottleGateTest {

    private val logBuffer = RingLogBuffer()

    /** A manual virtual clock for the non-timing tests; [gate] reads it live, so tests advance it by hand. */
    private var now = 0L

    /** random = 0.0 makes the equal-jitter draw deterministic (always the lower bound). */
    private fun gate(random: () -> Double = { 0.0 }) = AccountThrottleGate(nowMillis = { now }, random = random)

    @Before
    fun setUp() {
        // AppLog forwards to android.util.Log, a throwing no-op stub under plain JVM unit tests.
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        AppLog.install(logBuffer)
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `onThrottle marks the account throttled and returns the backoff`() {
        val gate = gate()

        val backoff = gate.onThrottle("acct", ThrottleSignal(ThrottleKind.RATE_LIMIT))

        assertTrue(backoff > 0L)
        assertTrue(gate.isThrottled("acct"))
        assertEquals(backoff, gate.remainingBackoffMillis("acct"))
    }

    @Test
    fun `repeated throttles escalate the backoff window`() {
        val gate = gate()

        val first = gate.onThrottle("acct", ThrottleSignal(ThrottleKind.RATE_LIMIT))
        val second = gate.onThrottle("acct", ThrottleSignal(ThrottleKind.RATE_LIMIT))

        assertTrue(second > first, "a consecutive throttle must back off longer ($second !> $first)")
    }

    @Test
    fun `onSuccess clears the backoff and resets the attempt count`() {
        val gate = gate()

        val first = gate.onThrottle("acct", ThrottleSignal(ThrottleKind.RATE_LIMIT))
        gate.onThrottle("acct", ThrottleSignal(ThrottleKind.RATE_LIMIT)) // escalate to attempt 2
        gate.onSuccess("acct")

        assertFalse(gate.isThrottled("acct"))
        // A fresh throttle after recovery starts back at the base (attempt 1) delay.
        assertEquals(first, gate.onThrottle("acct", ThrottleSignal(ThrottleKind.RATE_LIMIT)))
    }

    @Test
    fun `a throttled account never stalls a healthy one`() {
        val gate = gate()

        gate.onThrottle("throttled", ThrottleSignal(ThrottleKind.LOCKOUT))

        assertTrue(gate.isThrottled("throttled"))
        assertFalse(gate.isThrottled("healthy"))
        assertEquals(0L, gate.remainingBackoffMillis("healthy"))
    }

    @Test
    fun `an elapsed window stops throttling but still escalates a re-throttle`() {
        val gate = gate()

        val first = gate.onThrottle("acct", ThrottleSignal(ThrottleKind.RATE_LIMIT))
        now += first // the window elapses
        assertFalse(gate.isThrottled("acct"), "the account is free once its window passes")

        // Re-throttling before any success keeps the attempt count — it escalates, not restarts.
        val next = gate.onThrottle("acct", ThrottleSignal(ThrottleKind.RATE_LIMIT))
        assertTrue(next > first)
    }

    @Test
    fun `the throttle window clears exactly when the backoff elapses`() = runTest {
        val gate = AccountThrottleGate(nowMillis = { testScheduler.currentTime }, random = { 0.0 })

        val backoff = gate.onThrottle("acct", ThrottleSignal(ThrottleKind.RATE_LIMIT))
        assertTrue(gate.isThrottled("acct"))

        advanceTimeBy(backoff - 1)
        assertTrue(gate.isThrottled("acct"), "still throttled just before the window elapses")

        advanceTimeBy(1)
        assertFalse(gate.isThrottled("acct"), "cleared the instant the backoff elapses")
        assertEquals(0L, gate.remainingBackoffMillis("acct"))
    }

    @Test
    fun `throttle and clear log PII-free breadcrumbs`() {
        val gate = gate()

        gate.onThrottle("outlook:user@example.org", ThrottleSignal(ThrottleKind.LOCKOUT))
        gate.onSuccess("outlook:user@example.org")

        val messages = logBuffer.snapshot().map { it.message }
        assertTrue(messages.any { it.startsWith("throttled outlook:") && it.contains("kind=LOCKOUT") })
        assertTrue(messages.any { it.startsWith("throttle cleared outlook:") })
        messages.forEach { assertFalse(it.contains("user@example.org"), it) }
    }

    @Test
    fun `onSuccess on a healthy account is silent`() {
        gate().onSuccess("acct")

        assertTrue(logBuffer.snapshot().isEmpty())
    }
}
