// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * On-device proof of issue #356's backfill pacing, on the REAL Android coroutine runtime (not
 * coroutines-test virtual time) across the CI API matrix. A real [BackfillPacer] — the primitive that
 * bounds how hard one [BackfillWorker] run drives [MailBackfiller] — must:
 *
 * - keep making forward progress across successive paced runs, so a large mailbox still fills fully even
 *   though the per-run cap ends each run early (the DoD ask: history keeps filling across runs);
 * - skip its inter-slice cooldown while an interactive fetch is active, so it never stacks a fixed delay on
 *   top of #355's per-page park (no pathological double-delay);
 * - end a run promptly when cancelled mid-cooldown, so a WorkManager stop / teardown is never blocked.
 *
 * Deliberately mock-free (no `mockk`, no framework `Context`): the pacer's only collaborator is a real
 * [InteractiveImapGate] and a "slice" is a plain lambda whose result the test scripts, so this exercises
 * the genuine pacing on real threads and is maximally portable across API 29-37 (and dodges the
 * mockk-on-framework-types landmines). The JVM [BackfillPacerTest] covers the exact cooldown *timing* and
 * cap arithmetic under virtual time; this proves the same contract survives the real dispatcher.
 *
 * Tests that must not pay the real 30 s cooldown hold an interactive fetch active for their duration (which
 * legitimately suppresses the cooldown), so they finish in milliseconds; the one test that deliberately
 * lets a real cooldown start cancels it long before it elapses.
 */
@RunWith(AndroidJUnit4::class)
class BackfillPacerInstrumentedTest {

    @Test
    fun backfillFillsAllHistoryAcrossSuccessivePacedRunsDespiteThePerRunCap() = runBlocking<Unit> {
        val gate = InteractiveImapGate()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        // Hold an interactive fetch for the whole test so the pacer legitimately skips its real cooldown —
        // keeping the run loop fast while still exercising the cap + cross-run continuation on real threads.
        val interactive = launch(Dispatchers.Default) {
            gate.withInteractive {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()

        val pacer = BackfillPacer(gate)
        val remaining = AtomicInteger(TOTAL_PAGES)
        val slicesRun = AtomicInteger(0)
        // Each slice fills one page; true == more pages remain (exactly MailBackfiller.runBackfill's contract).
        val slice: suspend () -> Boolean = {
            slicesRun.incrementAndGet()
            remaining.decrementAndGet() > 0
        }

        var runs = 0
        var moreWork = true
        while (moreWork && runs < MAX_RUNS_GUARD) {
            moreWork = withTimeout(HAND_OFF_TIMEOUT_MS) { pacer.runPaced(shouldContinue = { true }, slice = slice) }
            runs++
        }

        assertFalse("history must finish across successive paced runs", moreWork)
        assertEquals("every page is filled exactly once — no pacing-induced gap", TOTAL_PAGES, slicesRun.get())
        assertTrue("the per-run cap must force more than one run for a $TOTAL_PAGES-page mailbox", runs > 1)

        release.complete(Unit)
        interactive.join()
    }

    @Test
    fun interactiveActivitySkipsTheInterSliceCooldownSoAPacedRunIsNotDoubleDelayed() = runBlocking<Unit> {
        val gate = InteractiveImapGate()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val interactive = launch(Dispatchers.Default) {
            gate.withInteractive {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()

        val pacer = BackfillPacer(gate)
        val results = ArrayDeque(listOf(true, true, false)) // two inter-slice gaps
        val slice: suspend () -> Boolean = { results.removeFirst() }

        // If the cooldown were NOT skipped while interactive, two real 30 s waits would blow this bound;
        // skipping them makes the run return in milliseconds.
        val moreWork = withTimeout(HAND_OFF_TIMEOUT_MS) {
            pacer.runPaced(shouldContinue = { true }, slice = slice)
        }

        assertFalse("the run still chains its slices to completion", moreWork)
        assertTrue("all scripted slices ran", results.isEmpty())

        release.complete(Unit)
        interactive.join()
    }

    @Test
    fun aCancelledRunEndsPromptlyWhileParkedInTheCooldownInsteadOfBlockingTeardown() = runBlocking<Unit> {
        // Idle gate: the cooldown is NOT skipped here, so the run genuinely parks in the ~30 s delay.
        val pacer = BackfillPacer(InteractiveImapGate())
        val slicesRun = AtomicInteger(0)
        val slice: suspend () -> Boolean = {
            slicesRun.incrementAndGet()
            true // always more work
        }

        val job = launch(Dispatchers.Default) { pacer.runPaced(shouldContinue = { true }, slice = slice) }

        delay(PARK_PROBE_MS) // let the first slice run and the run settle into the cooldown delay
        assertEquals("one slice ran, then the run parked in the cooldown", 1, slicesRun.get())

        job.cancel()
        // Must return far sooner than the 30 s cooldown — proof the cooldown is a cancellable delay.
        withTimeout(HAND_OFF_TIMEOUT_MS) { job.join() }

        assertEquals("cancelling during the cooldown must not start another slice", 1, slicesRun.get())
        assertTrue("the run ended by cancellation, not by running flat-out", job.isCancelled)
    }

    private companion object {
        /** A mailbox of enough pages that the per-run cap (4) must span several runs to fill it. */
        const val TOTAL_PAGES = 10

        /** Stops the run loop if a pacing regression somehow never reports done. */
        const val MAX_RUNS_GUARD = 20

        /** Slack given to a run to settle into its cooldown before we probe / cancel. */
        const val PARK_PROBE_MS = 300L

        /** Generous bound; only a real pacing/cancellation regression (a stuck cooldown) approaches it. */
        const val HAND_OFF_TIMEOUT_MS = 5_000L
    }
}
