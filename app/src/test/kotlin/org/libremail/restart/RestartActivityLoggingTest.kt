// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.restart

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.reporting.AppLog
import kotlin.test.assertNull

/**
 * Pins the #330 migration of [RestartActivity]'s one log site from raw `Log.w` to [AppLog.w].
 * [RestartActivity] itself is DEVICE-ONLY (multi-process kill/relaunch; see its KDoc) and can't be
 * exercised in a JVM test, so this characterizes the call it now makes instead of the class itself.
 *
 * That call runs in the ":restart" trampoline process, where `LibreMailApplication.onCreate` returns
 * early and never reaches `AppLog.install` — so [AppLog]'s buffer is null there, and this breadcrumb
 * reaches Logcat only, never a `DebugReport`. This is the one migrated site in the #324 epic whose
 * "buffer captured the breadcrumb" assertion intentionally does not apply (see #330); instead this
 * asserts the null-buffer shape: the call still forwards to Logcat and no-ops cleanly, matching the
 * raw `Log.w` behavior it replaced.
 */
class RestartActivityLoggingTest {

    // Mirrors RestartActivity's private TAG/message exactly (kept private there; duplicated here
    // rather than widening its visibility just for this test).
    private val tag = "LibreMailRestart"
    private val message = "no launch intent for org.libremail.app; cannot relaunch after restart"

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
        AppLog::class.java.getDeclaredField("buffer").apply { isAccessible = true }.set(AppLog, null)
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `RestartActivity's no-launch-intent warning forwards to Logcat and no-ops with no buffer installed`() {
        AppLog.w(tag, message)

        verify { Log.w(tag, message) }
        val bufferField = AppLog::class.java.getDeclaredField("buffer").apply { isAccessible = true }
        assertNull(bufferField.get(AppLog), "the :restart process never installs a buffer; AppLog must not create one")
    }
}
