// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.security

import org.junit.Test
import kotlin.test.assertEquals

class AppLockGateTest {

    private val grace = 30_000L

    @Test
    fun `starts locked`() {
        assertEquals(LockState.LOCKED, AppLockGate(grace).state)
    }

    @Test
    fun `app-lock disabled always unlocks`() {
        val gate = AppLockGate(grace)
        assertEquals(LockState.UNLOCKED, gate.onForeground(now = 0, appLockEnabled = false))
        assertEquals(LockState.UNLOCKED, gate.state)
    }

    @Test
    fun `cold start with app-lock on stays locked until authenticated`() {
        val gate = AppLockGate(grace)
        assertEquals(LockState.LOCKED, gate.onForeground(now = 0, appLockEnabled = true))
    }

    @Test
    fun `authentication unlocks`() {
        val gate = AppLockGate(grace)
        gate.onForeground(now = 0, appLockEnabled = true)
        gate.onAuthenticated()
        assertEquals(LockState.UNLOCKED, gate.state)
    }

    @Test
    fun `returning within grace stays unlocked`() {
        val gate = AppLockGate(grace)
        gate.onForeground(now = 0, appLockEnabled = true)
        gate.onAuthenticated()
        gate.onBackground(now = 1_000)
        assertEquals(LockState.UNLOCKED, gate.onForeground(now = 1_000 + grace, appLockEnabled = true))
    }

    @Test
    fun `returning after grace re-locks`() {
        val gate = AppLockGate(grace)
        gate.onForeground(now = 0, appLockEnabled = true)
        gate.onAuthenticated()
        gate.onBackground(now = 1_000)
        assertEquals(LockState.LOCKED, gate.onForeground(now = 1_000 + grace + 1, appLockEnabled = true))
    }

    @Test
    fun `foreground without backgrounding keeps an unlocked session (e g rotation)`() {
        val gate = AppLockGate(grace)
        gate.onForeground(now = 0, appLockEnabled = true)
        gate.onAuthenticated()
        // ON_START fires again on a configuration change without an intervening ON_STOP.
        assertEquals(LockState.UNLOCKED, gate.onForeground(now = 5_000, appLockEnabled = true))
    }

    @Test
    fun `lock forces a re-authentication`() {
        val gate = AppLockGate(grace)
        gate.onForeground(now = 0, appLockEnabled = true)
        gate.onAuthenticated()
        gate.lock()
        assertEquals(LockState.LOCKED, gate.state)
        // A subsequent foreground must not silently unlock a force-locked gate.
        assertEquals(LockState.LOCKED, gate.onForeground(now = 100, appLockEnabled = true))
    }

    @Test
    fun `disabling app-lock unlocks even a force-locked gate`() {
        val gate = AppLockGate(grace)
        gate.lock()
        assertEquals(LockState.UNLOCKED, gate.onForeground(now = 100, appLockEnabled = false))
    }
}
