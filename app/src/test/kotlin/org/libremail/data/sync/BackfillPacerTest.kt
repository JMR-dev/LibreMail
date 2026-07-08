// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
 * [BackfillPacer] (issue #356) must pace one backfill run: cool down between chained slices, cap the
 * slices per run, and stop promptly on cancellation — all proven against coroutines-test virtual time
 * so no test ever real-sleeps. It must also *compose* with the two sibling mechanisms rather than fight
 * them: skip its fixed cooldown while an interactive fetch is active (#355, so it never double-delays on
 * top of that mechanism's park) and burn no cooldown when a slice reports done because its only account
 * was throttled (#360). Every breadcrumb it logs is PII-free (durations and counts only).
 *
 * Deliberately mock-free: the pacer's only collaborator is the real [InteractiveImapGate], and a slice is
 * modelled by a plain lambda whose return value the test scripts — so a pass is attributable purely to the
 * pacing logic. Mirrors [AccountThrottleGateTest]'s virtual-clock idiom.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackfillPacerTest {

    private val logBuffer = RingLogBuffer()
    private val interactiveGate = InteractiveImapGate()

    private fun pacer(
        cooldownMillis: Long = COOLDOWN_MS,
        maxSlicesPerRun: Int = MAX_SLICES,
        gate: InteractiveImapGate = interactiveGate,
    ) = BackfillPacer(gate, cooldownMillis, maxSlicesPerRun)

    @Before
    fun setUp() {
        // AppLog forwards to android.util.Log, a throwing no-op stub under plain JVM unit tests.
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        AppLog.install(logBuffer)
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `cools down for the configured delay between each chained slice`() = runTest {
        val results = ArrayDeque(listOf(true, true, false)) // 3 slices → 2 inter-slice gaps

        val more = pacer(maxSlicesPerRun = 10).runPaced({ true }) { results.removeFirst() }

        assertFalse(more, "a slice reporting no more work ends the run as done")
        assertTrue(results.isEmpty(), "every scripted slice ran")
        assertEquals(2 * COOLDOWN_MS, testScheduler.currentTime, "exactly two cooldowns separate the three slices")
        assertEquals(
            2,
            logBuffer.snapshot().count { it.message == "backfill cooldown ${COOLDOWN_MS}ms before next slice" },
            "each inter-slice gap logs one PII-free cooldown breadcrumb",
        )
    }

    @Test
    fun `caps the slices per run and reports history still has more to fill`() = runTest {
        var calls = 0

        // The slice never reports done, so only the cap can stop the run.
        val more = pacer(maxSlicesPerRun = 3).runPaced({ true }) { true.also { calls++ } }

        assertTrue(more, "a run stopped by the cap still has history to fill")
        assertEquals(3, calls, "the run stops exactly at the per-run cap")
        assertEquals(
            2 * COOLDOWN_MS,
            testScheduler.currentTime,
            "two cooldowns between the three slices; none after the cap",
        )
        val capMsg = "backfill run capped at 3 slice(s); deferring to periodic cadence"
        assertTrue(
            logBuffer.snapshot().any { it.message == capMsg },
            "capping logs a PII-free breadcrumb",
        )
    }

    @Test
    fun `a slice that reports done burns no cooldown (composes with the throttle-skip in #360)`() = runTest {
        var calls = 0

        // Mirrors #360: a slice whose only outstanding work was a throttled account returns moreWork=false.
        val more = pacer().runPaced({ true }) { false.also { calls++ } }

        assertFalse(more)
        assertEquals(1, calls, "the run ends after the single done slice")
        assertEquals(0L, testScheduler.currentTime, "no cooldown is spent when there is no follow-up slice")
        assertTrue(logBuffer.snapshot().none { it.message.startsWith("backfill cooldown") }, "and none is logged")
    }

    @Test
    fun `skips the cooldown while an interactive fetch is active (composes with #355, no double-delay)`() = runTest {
        val gate = InteractiveImapGate()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch {
            gate.withInteractive {
                started.complete(Unit)
                release.await()
            }
        }
        started.await() // the gate now reports an interactive fetch in flight

        val results = ArrayDeque(listOf(true, true, false))
        val more = pacer(maxSlicesPerRun = 10, gate = gate).runPaced({ true }) { results.removeFirst() }

        assertFalse(more)
        assertTrue(results.isEmpty(), "the run still chains its slices")
        assertEquals(
            0L,
            testScheduler.currentTime,
            "no fixed cooldown while interactive — #355's per-page park is the sole backpressure",
        )
        assertEquals(
            2,
            logBuffer.snapshot().count { it.message == "backfill cooldown skipped: interactive fetch in flight" },
            "each skipped gap logs a PII-free breadcrumb",
        )
        release.complete(Unit)
        holder.join()
    }

    @Test
    fun `a cancellation during the cooldown ends the run promptly without another slice`() = runTest {
        var calls = 0
        val job = launch { pacer(maxSlicesPerRun = 10).runPaced({ true }) { true.also { calls++ } } }

        runCurrent() // the first slice runs, then the run parks in the cooldown delay
        assertEquals(1, calls, "one slice ran, now parked in the cooldown")

        job.cancel()
        advanceUntilIdle()

        assertEquals(1, calls, "cancelling during the cooldown must not start another slice")
        assertTrue(job.isCancelled, "the cooldown is a cancellable delay, so teardown is never blocked")
    }

    @Test
    fun `attempts no slice at all when told not to continue (respects the worker's isStopped)`() = runTest {
        var calls = 0

        val more = pacer().runPaced(shouldContinue = { false }) { true.also { calls++ } }

        assertTrue(more, "no work attempted, so history may still remain")
        assertEquals(0, calls, "an already-stopped run pages nothing")
    }

    private companion object {
        const val COOLDOWN_MS = 30_000L
        const val MAX_SLICES = 4
    }
}
