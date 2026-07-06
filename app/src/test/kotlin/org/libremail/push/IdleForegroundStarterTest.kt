// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.push

import android.app.Service
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM coverage of [IdleForegroundStarter] — the dataSync foreground-start decision
 * `IdleService.onStartCommand` delegates to (#354). Extracted out of the Android `Service` precisely
 * so this branchy logic — skip while capped, catch the runtime-cap
 * `ForegroundServiceStartNotAllowedException` (surfaced via its [IllegalStateException] supertype) and
 * degrade, otherwise proceed — is unit-testable with no emulator and no Robolectric (this repo has
 * neither). It reads only the `Service.START_NOT_STICKY` constant; no `Service` is instantiated.
 */
class IdleForegroundStarterTest {

    @Test
    fun `a successful foreground start proceeds to onStarted and returns START_NOT_STICKY`() {
        var started = false
        var degradeCalled = false

        val result = IdleForegroundStarter.startForegroundOrDegrade(
            capActive = false,
            enterForeground = { /* startForeground succeeds */ },
            onStarted = { started = true },
            onDegraded = { degradeCalled = true },
        )

        assertEquals(Service.START_NOT_STICKY, result)
        assertTrue("onStarted must run after a successful foreground start", started)
        assertFalse("degrade must not run when the start succeeds", degradeCalled)
    }

    @Test
    fun `a runtime-cap rejection is caught, routed to degrade with the cause, and never propagates`() {
        // The exact platform rejection is a ForegroundServiceStartNotAllowedException (API 31+); here we
        // throw its IllegalStateException supertype, which is what the seam catches (and what the stub
        // android.jar lets us construct on the JVM).
        val rejection = IllegalStateException("Time limit already exhausted for foreground service type dataSync")
        var started = false
        var degradedWith: Throwable? = null

        // No exception escapes — that is the whole point of the fix (was an uncaught crash → restart loop).
        val result = IdleForegroundStarter.startForegroundOrDegrade(
            capActive = false,
            enterForeground = { throw rejection },
            onStarted = { started = true },
            onDegraded = { cause -> degradedWith = cause },
        )

        assertEquals(Service.START_NOT_STICKY, result)
        assertFalse("a rejected foreground start must not begin IDLE watching", started)
        assertSame("the rejection cause must reach the degrade path", rejection, degradedWith)
    }

    @Test
    fun `an active cap window skips the foreground start entirely and degrades with no cause`() {
        var enterForegroundAttempted = false
        var started = false
        var degradeCalled = false
        var degradedWith: Throwable? = null

        val result = IdleForegroundStarter.startForegroundOrDegrade(
            capActive = true,
            enterForeground = { enterForegroundAttempted = true },
            onStarted = { started = true },
            onDegraded = { cause ->
                degradeCalled = true
                degradedWith = cause
            },
        )

        assertEquals(Service.START_NOT_STICKY, result)
        assertFalse("must not attempt a dataSync FGS start while still inside the cap window", enterForegroundAttempted)
        assertFalse(started)
        assertTrue("must still degrade to periodic sync while capped", degradeCalled)
        assertNull("the cap-window skip carries no throwable cause", degradedWith)
    }

    @Test
    fun `a non-IllegalStateException from the foreground start propagates unchanged`() {
        // Only the runtime-cap/background ISE is a safe-degrade condition; anything else (e.g. a
        // SecurityException) is a genuine bug we must not swallow.
        val boom = SecurityException("not an FGS runtime-cap rejection")
        var degradeCalled = false

        val thrown = runCatching {
            IdleForegroundStarter.startForegroundOrDegrade(
                capActive = false,
                enterForeground = { throw boom },
                onStarted = {},
                onDegraded = { degradeCalled = true },
            )
        }.exceptionOrNull()

        assertSame("an unrelated failure must propagate, not degrade", boom, thrown)
        assertFalse("degrade must not run for a non-ISE failure", degradeCalled)
    }
}
