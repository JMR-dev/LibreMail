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
 * On-device proof of issue #355's interactive-vs-backfill coordination, on the REAL Android coroutine
 * runtime (not coroutines-test virtual time) across the CI API matrix. A real [InteractiveImapGate] — the
 * process-wide priority signal the reader's on-demand IMAP fetch and the background full-history backfill
 * share — must:
 *
 * - park a backfill-style waiter ([InteractiveImapGate.awaitInteractiveIdle], used verbatim by
 *   [MailBackfiller.yieldToInteractive]) for as long as an interactive fetch is in flight, and resume it
 *   the instant the fetch clears;
 * - stay held across several overlapping interactive fetches until the last releases;
 * - release even when a wrapped fetch throws — so backfill can never deadlock behind a failed message open.
 *
 * Deliberately mock-free (no `mockk`, no framework `Context`): the gate is the whole synchronisation
 * primitive #355 adds, so exercising it directly is both the faithful behavioural test and the most
 * portable across API 29–37. The JVM `InteractiveImapGateTest` / `MailBackfillerTest` cover the same
 * contract plus the full backfiller wiring under coroutines-test.
 */
@RunWith(AndroidJUnit4::class)
class InteractiveImapGateInstrumentedTest {

    @Test
    fun interactiveFetchParksABackfillWaiterUntilItReleasesThenResumesIt() = runBlocking<Unit> {
        val gate = InteractiveImapGate()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        // An interactive fetch (e.g. openMessage) holds the gate until we release it.
        val interactive = launch(Dispatchers.Default) {
            gate.withInteractive {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        assertTrue("the gate is held while the interactive fetch runs", gate.isInteractiveActive())

        // A backfill-style waiter yields to the gate exactly as MailBackfiller does before each page.
        val pagesFetched = AtomicInteger(0)
        val backfill = launch(Dispatchers.Default) {
            gate.awaitInteractiveIdle()
            pagesFetched.incrementAndGet() // stands in for the next server page
        }
        delay(PARK_PROBE_MS)
        assertEquals("backfill must park while the interactive fetch holds the gate", 0, pagesFetched.get())

        release.complete(Unit)
        withTimeout(HAND_OFF_TIMEOUT_MS) { backfill.join() }
        assertEquals("backfill pages once the interactive fetch clears", 1, pagesFetched.get())
        assertFalse("the gate clears after the fetch releases", gate.isInteractiveActive())
        interactive.join()
    }

    @Test
    fun anErroredInteractiveFetchStillReleasesTheGateSoBackfillNeverDeadlocks() = runBlocking<Unit> {
        val gate = InteractiveImapGate()

        val failed = runCatching { gate.withInteractive { throw IllegalStateException("open failed") } }

        assertTrue("the failing fetch propagates to the caller", failed.isFailure)
        assertFalse("a failed fetch must not strand the gate held", gate.isInteractiveActive())
        // A backfill waiter must return at once now; a regression would hang until the timeout fires.
        withTimeout(HAND_OFF_TIMEOUT_MS) { gate.awaitInteractiveIdle() }
    }

    @Test
    fun theGateStaysHeldUntilTheLastOfSeveralConcurrentFetchesReleases() = runBlocking<Unit> {
        val gate = InteractiveImapGate()
        val entered1 = CompletableDeferred<Unit>()
        val entered2 = CompletableDeferred<Unit>()
        val release1 = CompletableDeferred<Unit>()
        val release2 = CompletableDeferred<Unit>()

        val holder1 = launch(Dispatchers.Default) {
            gate.withInteractive {
                entered1.complete(Unit)
                release1.await()
            }
        }
        val holder2 = launch(Dispatchers.Default) {
            gate.withInteractive {
                entered2.complete(Unit)
                release2.await()
            }
        }
        entered1.await()
        entered2.await()
        assertEquals("both concurrent fetches count", 2, gate.activeInteractiveCount.value)

        val resumed = CompletableDeferred<Unit>()
        launch(Dispatchers.Default) {
            gate.awaitInteractiveIdle()
            resumed.complete(Unit)
        }

        release1.complete(Unit)
        delay(PARK_PROBE_MS)
        assertFalse("still parked while one interactive fetch remains in flight", resumed.isCompleted)

        release2.complete(Unit)
        withTimeout(HAND_OFF_TIMEOUT_MS) { resumed.await() }
        assertEquals("the gate clears only after the last fetch releases", 0, gate.activeInteractiveCount.value)
        holder1.join()
        holder2.join()
    }

    private companion object {
        /** Slack given to a parked waiter to (wrongly) resume before we assert it is still parked. */
        const val PARK_PROBE_MS = 300L

        /** Generous bound for the gate hand-off; only a real park/resume regression approaches it. */
        const val HAND_OFF_TIMEOUT_MS = 5_000L
    }
}
