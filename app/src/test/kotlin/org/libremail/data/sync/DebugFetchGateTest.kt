// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the debug-only fetch gate (issue #393): its default not-paused state, pause/resume/query
 * per [FetchScope], the `all` alias + scope-string parsing the receiver relies on, the ordered-broadcast
 * read-back string, and thread-safety of the in-memory holder. [DebugFetchGate] is a process-global
 * object, so each test resets it to isolate from the others.
 */
class DebugFetchGateTest {

    @Before
    @After
    fun resetGate() = DebugFetchGate.reset()

    @Test
    fun `defaults to nothing paused for every scope`() {
        FetchScope.entries.forEach { scope ->
            assertFalse(DebugFetchGate.isPaused(scope), "$scope must default to not-paused")
        }
        assertTrue(DebugFetchGate.pausedScopes().isEmpty())
        assertEquals("paused=[]", DebugFetchGate.pausedResult())
    }

    @Test
    fun `pausing one scope leaves the other live`() {
        DebugFetchGate.pause(setOf(FetchScope.BACKFILL))

        assertTrue(DebugFetchGate.isPaused(FetchScope.BACKFILL))
        assertFalse(DebugFetchGate.isPaused(FetchScope.PREFETCH))
        assertEquals(setOf(FetchScope.BACKFILL), DebugFetchGate.pausedScopes())
        assertEquals("paused=[backfill]", DebugFetchGate.pausedResult())
    }

    @Test
    fun `pausing both scopes reports both, in declaration order`() {
        DebugFetchGate.pause(setOf(FetchScope.PREFETCH, FetchScope.BACKFILL))

        assertTrue(DebugFetchGate.isPaused(FetchScope.BACKFILL))
        assertTrue(DebugFetchGate.isPaused(FetchScope.PREFETCH))
        // Declaration order (BACKFILL before PREFETCH), regardless of the set's insertion order.
        assertEquals("paused=[backfill,prefetch]", DebugFetchGate.pausedResult())
    }

    @Test
    fun `pause is additive across calls`() {
        DebugFetchGate.pause(setOf(FetchScope.BACKFILL))
        DebugFetchGate.pause(setOf(FetchScope.PREFETCH))

        assertEquals(setOf(FetchScope.BACKFILL, FetchScope.PREFETCH), DebugFetchGate.pausedScopes())
    }

    @Test
    fun `resume clears only the named scope`() {
        DebugFetchGate.pause(setOf(FetchScope.BACKFILL, FetchScope.PREFETCH))

        DebugFetchGate.resume(setOf(FetchScope.BACKFILL))

        assertFalse(DebugFetchGate.isPaused(FetchScope.BACKFILL))
        assertTrue(DebugFetchGate.isPaused(FetchScope.PREFETCH))
        assertEquals("paused=[prefetch]", DebugFetchGate.pausedResult())
    }

    @Test
    fun `resume of an unpaused scope is a no-op`() {
        DebugFetchGate.resume(setOf(FetchScope.BACKFILL))

        assertEquals("paused=[]", DebugFetchGate.pausedResult())
    }

    @Test
    fun `empty pause and resume are no-ops`() {
        DebugFetchGate.pause(emptySet())
        assertEquals("paused=[]", DebugFetchGate.pausedResult())

        DebugFetchGate.pause(setOf(FetchScope.PREFETCH))
        DebugFetchGate.resume(emptySet())
        assertEquals("paused=[prefetch]", DebugFetchGate.pausedResult())
    }

    @Test
    fun `reset clears every pause`() {
        DebugFetchGate.pause(setOf(FetchScope.BACKFILL, FetchScope.PREFETCH))

        DebugFetchGate.reset()

        assertTrue(DebugFetchGate.pausedScopes().isEmpty())
    }

    // --- FetchScope.parse (the scope-string contract the receiver depends on) -------------------------

    @Test
    fun `parse maps a comma list of known scopes`() {
        assertEquals(setOf(FetchScope.BACKFILL, FetchScope.PREFETCH), FetchScope.parse("backfill,prefetch"))
        assertEquals(setOf(FetchScope.BACKFILL), FetchScope.parse("backfill"))
    }

    @Test
    fun `parse expands the all alias to every scope`() {
        assertEquals(FetchScope.entries.toSet(), FetchScope.parse("all"))
        // The alias wins even mixed with other tokens.
        assertEquals(FetchScope.entries.toSet(), FetchScope.parse("backfill,all"))
    }

    @Test
    fun `parse is case- and whitespace-insensitive`() {
        assertEquals(setOf(FetchScope.BACKFILL, FetchScope.PREFETCH), FetchScope.parse("  BACKFILL , Prefetch "))
    }

    @Test
    fun `parse ignores unknown and blank tokens`() {
        assertEquals(setOf(FetchScope.PREFETCH), FetchScope.parse("prefetch,bogus,,"))
        assertTrue(FetchScope.parse("nope").isEmpty())
    }

    @Test
    fun `parse of null or blank is the empty set`() {
        assertTrue(FetchScope.parse(null).isEmpty())
        assertTrue(FetchScope.parse("").isEmpty())
        assertTrue(FetchScope.parse("   ").isEmpty())
    }

    // --- thread-safety --------------------------------------------------------------------------------

    @Test
    fun `concurrent pause and resume never corrupts the holder`() {
        val threads = 8
        val iterations = 2_000
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until threads).map { index ->
                pool.submit {
                    start.await()
                    val scope = FetchScope.entries[index % FetchScope.entries.size]
                    repeat(iterations) {
                        DebugFetchGate.pause(setOf(scope))
                        DebugFetchGate.isPaused(scope)
                        DebugFetchGate.pausedResult()
                        DebugFetchGate.resume(setOf(scope))
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        // No exception above (a data race on a plain HashSet would throw), and the holder is consistent:
        // pausedScopes() only ever reports declared scopes.
        assertTrue(DebugFetchGate.pausedScopes().all { it in FetchScope.entries })
    }
}
