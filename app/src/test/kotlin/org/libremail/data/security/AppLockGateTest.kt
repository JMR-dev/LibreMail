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

    @Test
    fun `a background recorded after a foreground pass began does not unlock on that stale pass`() {
        // Regression (lock-bypass race): a stale foreground pass whose timestamp was captured before a
        // later background must NOT treat that background as a within-grace return and stay unlocked.
        val gate = AppLockGate(grace)
        gate.onForeground(now = 0, appLockEnabled = true)
        gate.onAuthenticated() // UNLOCKED
        val staleForegroundAt = 1_000L
        gate.onBackground(now = 2_000L) // background happens AFTER the stale pass began
        assertEquals(LockState.LOCKED, gate.onForeground(now = staleForegroundAt, appLockEnabled = true))
    }

    @Test
    fun `the genuine return after a stale pass still requires authentication`() {
        val gate = AppLockGate(grace)
        gate.onForeground(now = 0, appLockEnabled = true)
        gate.onAuthenticated()
        gate.onBackground(now = 2_000L)
        gate.onForeground(now = 1_000L, appLockEnabled = true) // stale -> LOCKED, marker retained
        assertEquals(LockState.LOCKED, gate.onForeground(now = 3_000L, appLockEnabled = true))
    }

    @Test
    fun `grace survives activity recreation when the gate instance is reused (app-scoped singleton)`() {
        // #101: Back on the task root (API 29/30) finishes the Activity and clears its ViewModelStore,
        // so AppLockViewModel is destroyed and re-created. Because the gate is application-scoped the
        // SAME instance is reused across that recreation: the background marker recorded before the
        // store was cleared is still present, so returning within grace stays unlocked — identical to
        // leaving via Home and returning.
        val survivingGate = AppLockGate(grace)
        survivingGate.onForeground(now = 0, appLockEnabled = true)
        survivingGate.onAuthenticated()
        survivingGate.onBackground(now = 1_000) // ON_STOP as Back finishes the Activity
        // ...Activity destroyed + re-created; the same singleton gate is handed to the new ViewModel...
        assertEquals(
            LockState.UNLOCKED,
            survivingGate.onForeground(now = 1_000 + grace, appLockEnabled = true),
        )
    }

    @Test
    fun `grace still expires across activity recreation when the reused gate is out of the window`() {
        val survivingGate = AppLockGate(grace)
        survivingGate.onForeground(now = 0, appLockEnabled = true)
        survivingGate.onAuthenticated()
        survivingGate.onBackground(now = 1_000)
        assertEquals(
            LockState.LOCKED,
            survivingGate.onForeground(now = 1_000 + grace + 1, appLockEnabled = true),
        )
    }

    @Test
    fun `a fresh gate (process death, or the pre-fix Activity-scoped bug) starts locked`() {
        // Contrast with the reused instance above: if the gate were Activity/ViewModel-scoped (the
        // pre-fix bug) or after a genuine cold start (process death), recreation builds a FRESH gate.
        // It must start LOCKED and stay locked on the first foreground even within the grace window —
        // the re-auth-on-Back inconsistency #101 fixes, and the correct cold-start behavior.
        val freshGate = AppLockGate(grace)
        assertEquals(LockState.LOCKED, freshGate.state)
        assertEquals(LockState.LOCKED, freshGate.onForeground(now = 1_000, appLockEnabled = true))
    }
}
