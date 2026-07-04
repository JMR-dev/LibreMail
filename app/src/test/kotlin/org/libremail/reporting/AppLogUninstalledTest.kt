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

/**
 * Complements [AppLogTest] with the "no buffer installed yet" branch (issue #289): before [AppLog]
 * is wired to a [RingLogBuffer] — e.g. very early startup — every level must still forward to Logcat
 * without touching the (absent) buffer. `android.util.Log` is a no-op stub in JVM tests, so it is
 * statically mocked; the singleton's buffer is reset to null via reflection so this exercises the
 * null-buffer path rather than whatever a prior test installed.
 */
class AppLogUninstalledTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        AppLog::class.java.getDeclaredField("buffer").apply { isAccessible = true }.set(AppLog, null)
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `logging before a buffer is installed only forwards to Logcat`() {
        AppLog.d("Tag", "debug")
        AppLog.i("Tag", "info")
        AppLog.w("Tag", "warn")
        AppLog.e("Tag", "error")
        AppLog.e("Tag", "error with cause", IllegalStateException("boom"))

        verify { Log.d("Tag", "debug") }
        verify { Log.i("Tag", "info") }
        verify { Log.w("Tag", "warn") }
        verify { Log.e("Tag", "error", null) }
        verify { Log.e("Tag", "error with cause", any<IllegalStateException>()) }
    }
}
