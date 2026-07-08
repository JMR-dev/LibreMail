// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [InteractiveImapGate] (issue #355) must let a foreground/interactive IMAP fetch pre-empt background
 * backfill: raise an in-flight count for the duration of [InteractiveImapGate.withInteractive], park
 * [InteractiveImapGate.awaitInteractiveIdle] until the count clears, hold across several concurrent
 * fetches until the last releases, and — critically — always release even when the wrapped fetch throws
 * so backfill can never deadlock behind a failed open. The concurrency tests mirror
 * [MailMaintenanceGateTest]'s real-dispatcher idiom (runBlocking + Dispatchers.Default + CompletableDeferred)
 * rather than virtual time, since the contract under test is precisely cross-coroutine hand-off.
 */
class InteractiveImapGateTest {

    @Test
    fun `an idle gate reports inactive and awaitInteractiveIdle returns at once`() = runTest {
        val gate = InteractiveImapGate()

        assertFalse(gate.isInteractiveActive())
        assertEquals(0, gate.activeInteractiveCount.value)
        // Must not suspend forever when nothing is in flight.
        withTimeout(2_000) { gate.awaitInteractiveIdle() }
    }

    @Test
    fun `withInteractive releases the gate even when the block throws (no deadlock)`() = runTest {
        val gate = InteractiveImapGate()
        val boom = IllegalStateException("interactive fetch failed")

        val thrown = runCatching { gate.withInteractive { throw boom } }.exceptionOrNull()

        assertSame(boom, thrown, "the original throwable propagates to the caller's runCatching")
        assertFalse(gate.isInteractiveActive(), "a failed fetch must not strand the counter above zero")
        assertEquals(0, gate.activeInteractiveCount.value)
        // A backfill parked on this gate would resume immediately now — prove it does not hang.
        withTimeout(2_000) { gate.awaitInteractiveIdle() }
    }

    @Test
    fun `awaitInteractiveIdle parks while a fetch is in flight and resumes when it releases`() = runBlocking<Unit> {
        val gate = InteractiveImapGate()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val holder = launch(Dispatchers.Default) {
            gate.withInteractive {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await() // the interactive fetch is now in flight
        assertTrue(gate.isInteractiveActive())
        assertEquals(1, gate.activeInteractiveCount.value)

        val resumed = CompletableDeferred<Unit>()
        launch(Dispatchers.Default) {
            gate.awaitInteractiveIdle()
            resumed.complete(Unit)
        }
        delay(PARK_PROBE_MS)
        assertFalse(resumed.isCompleted, "the waiter must stay parked while the interactive fetch holds the gate")

        release.complete(Unit)
        withTimeout(2_000) { resumed.await() }
        assertFalse(gate.isInteractiveActive(), "the gate clears once the fetch releases")
        holder.join()
    }

    @Test
    fun `the gate stays held until the last of several concurrent fetches releases`() = runBlocking<Unit> {
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
        assertEquals(2, gate.activeInteractiveCount.value, "both concurrent fetches count")

        val resumed = CompletableDeferred<Unit>()
        launch(Dispatchers.Default) {
            gate.awaitInteractiveIdle()
            resumed.complete(Unit)
        }

        release1.complete(Unit)
        delay(PARK_PROBE_MS)
        assertFalse(resumed.isCompleted, "still parked while one interactive fetch remains in flight")
        assertEquals(1, gate.activeInteractiveCount.value)

        release2.complete(Unit)
        withTimeout(2_000) { resumed.await() }
        assertEquals(0, gate.activeInteractiveCount.value, "the gate clears only after the last fetch releases")
        holder1.join()
        holder2.join()
    }

    @Test
    fun `activeInteractiveCount emits the rise and fall around an interactive fetch`() = runBlocking<Unit> {
        val gate = InteractiveImapGate()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        gate.activeInteractiveCount.test {
            assertEquals(0, awaitItem(), "starts idle")
            val holder = launch(Dispatchers.Default) {
                gate.withInteractive {
                    entered.complete(Unit)
                    release.await()
                }
            }
            entered.await()
            assertEquals(1, awaitItem(), "rises when the fetch starts")
            release.complete(Unit)
            assertEquals(0, awaitItem(), "falls when the fetch completes")
            holder.join()
            cancelAndIgnoreRemainingEvents()
        }
    }

    private companion object {
        /** Slack given to a parked coroutine to (wrongly) resume before we assert it is still parked. */
        const val PARK_PROBE_MS = 200L
    }
}
