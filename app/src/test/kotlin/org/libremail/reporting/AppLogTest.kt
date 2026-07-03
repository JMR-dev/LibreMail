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
import kotlin.test.assertEquals

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
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
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
        AppLog.e("Tag", "error with cause", IllegalStateException("boom"))

        val snapshot = buffer.snapshot()
        assertEquals(
            listOf('D', 'I', 'W', 'E', 'E'),
            snapshot.map { it.level },
        )
        assertEquals(
            listOf("debug line", "info line", "warn line", "error line", "error with cause"),
            snapshot.map { it.message },
        )
        verify { Log.d("Tag", "debug line") }
        verify { Log.i("Tag", "info line") }
        verify { Log.w("Tag", "warn line") }
        verify { Log.e("Tag", "error line", null) }
    }
}
