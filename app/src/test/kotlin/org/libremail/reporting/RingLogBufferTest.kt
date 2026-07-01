// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.reporting

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RingLogBufferTest {

    @Test
    fun `records lines in order`() {
        val buffer = RingLogBuffer()

        buffer.record('I', "Tag", "first")
        buffer.record('W', "Tag", "second")

        val snapshot = buffer.snapshot()
        assertEquals(2, snapshot.size)
        assertEquals("first", snapshot[0].message)
        assertEquals("second", snapshot[1].message)
        assertEquals('W', snapshot[1].level)
    }

    @Test
    fun `caps capacity and drops the oldest entries`() {
        val buffer = RingLogBuffer()

        repeat(TOTAL) { buffer.record('D', "Tag", "msg-$it") }

        val snapshot = buffer.snapshot()
        assertEquals(CAPACITY, snapshot.size)
        // The most recent entry is retained; the very first was dropped.
        assertEquals("msg-${TOTAL - 1}", snapshot.last().message)
        assertTrue(snapshot.none { it.message == "msg-0" })
    }

    @Test
    fun `clear empties the buffer`() {
        val buffer = RingLogBuffer()
        buffer.record('I', "Tag", "x")

        buffer.clear()

        assertTrue(buffer.snapshot().isEmpty())
    }

    private companion object {
        const val CAPACITY = 200
        const val TOTAL = 250
    }
}
