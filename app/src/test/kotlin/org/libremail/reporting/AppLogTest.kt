// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.reporting

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.ConnectException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [AppLog] mirrors every Logcat call into the process [RingLogBuffer] so recent activity can be
 * attached to a crash report. `android.util.Log` is a no-op stub in JVM tests, so it is statically
 * mocked; the assertions are on what lands in the buffer.
 */
class AppLogTest {

    private val buffer = RingLogBuffer()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.d(any(), any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        AppLog.install(buffer)
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `each level records its line into the buffer and forwards to Logcat`() {
        AppLog.d("Tag", "debug line")
        AppLog.i("Tag", "info line")
        AppLog.w("Tag", "warn line")
        AppLog.e("Tag", "error line")

        val snapshot = buffer.snapshot()
        assertEquals(
            listOf('D', 'I', 'W', 'E'),
            snapshot.map { it.level },
        )
        assertEquals(
            listOf("debug line", "info line", "warn line", "error line"),
            snapshot.map { it.message },
        )
        verify { Log.d("Tag", "debug line") }
        verify { Log.i("Tag", "info line") }
        verify { Log.w("Tag", "warn line") }
        verify { Log.e("Tag", "error line", null) }
    }

    @Test
    fun `e with a throwable records the scrubbed trace - class and frames kept, host and email stripped`() {
        val cause = ConnectException("Failed to connect to imap.example.com/93.184.216.34:993 for user@example.com")

        AppLog.e("Net", "connect failed", cause)

        val entry = buffer.snapshot().single()
        assertEquals('E', entry.level)
        // The caller message stays, and the throwable's class name + a frame are kept for usefulness.
        assertTrue(entry.message.startsWith("connect failed\n"), entry.message)
        assertTrue(entry.message.contains("java.net.ConnectException"), entry.message)
        assertTrue(entry.message.contains("at org.libremail.reporting.AppLogTest"), entry.message)
        // PII embedded in the exception message is gone: host, ip, port and email.
        assertFalse(entry.message.contains("imap.example.com"), entry.message)
        assertFalse(entry.message.contains("93.184.216.34"), entry.message)
        assertFalse(entry.message.contains(":993"), entry.message)
        assertFalse(entry.message.contains("user@example.com"), entry.message)
        assertFalse(entry.message.contains("example"), entry.message)
        verify { Log.e("Net", "connect failed", cause) }
    }

    @Test
    fun `w and d throwable overloads record the scrubbed trace and forward the throwable to Logcat`() {
        val cause = ConnectException("auth failed for user@example.org at imap.mail.example.org:143")

        AppLog.w("Net", "warn cause", cause)
        AppLog.d("Net", "debug cause", cause)

        val snapshot = buffer.snapshot()
        assertEquals(listOf('W', 'D'), snapshot.map { it.level })
        assertEquals(listOf("warn cause", "debug cause"), snapshot.map { it.message.substringBefore('\n') })
        snapshot.forEach { entry ->
            assertTrue(entry.message.contains("java.net.ConnectException"), entry.message)
            assertFalse(entry.message.contains("user@example.org"), entry.message)
            assertFalse(entry.message.contains("imap.mail.example.org"), entry.message)
            assertFalse(entry.message.contains(":143"), entry.message)
            assertFalse(entry.message.contains("example"), entry.message)
        }
        verify { Log.w("Net", "warn cause", cause) }
        verify { Log.d("Net", "debug cause", cause) }
    }

    @Test
    fun `a null throwable records the message alone`() {
        AppLog.w("Net", "just a warning", null)

        assertEquals("just a warning", buffer.snapshot().single().message)
    }
}
