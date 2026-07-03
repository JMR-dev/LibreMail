// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.reporting

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LogEntryTest {

    @Test
    fun `formatted renders the timestamp, level, tag, and message`() {
        val formatted = LogEntry(timeMillis = 0L, level = 'W', tag = "Sync", message = "hello").formatted()

        assertTrue(formatted.contains("W/Sync: hello"), formatted)
        assertTrue(formatted.contains("1970-01-01"), formatted)
    }

    @Test
    fun `carries value semantics`() {
        val entry = LogEntry(timeMillis = 5L, level = 'I', tag = "Tag", message = "msg")

        assertEquals(5L, entry.timeMillis)
        assertEquals('I', entry.level)
        assertEquals("Tag", entry.tag)
        assertEquals("msg", entry.message)

        assertEquals(entry, entry.copy())
        assertEquals(entry.hashCode(), entry.copy().hashCode())
        assertTrue(entry.toString().contains("msg"))
        assertNotEquals(entry, entry.copy(message = "other"))
        assertNotEquals(entry, entry.copy(level = 'E'))
        assertNotEquals(entry, entry.copy(timeMillis = 6L))
    }
}
