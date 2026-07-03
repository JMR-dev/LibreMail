// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.lock

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Value semantics for the [AppLockUiState] holder. The [AppLockViewModel] behaviour that produces
 * these states is covered exhaustively in [AppLockViewModelTest]; this pins the sealed hierarchy's own
 * equality/nonce contract (the nonce is what lets two identical-error locks still update the UI).
 */
class AppLockUiStateTest {

    @Test
    fun `Locked carries value semantics including its distinguishing nonce`() {
        val locked = AppLockUiState.Locked(error = "boom", nonce = 3)

        val (error, nonce) = locked
        assertEquals("boom", error)
        assertEquals(3, nonce)
        assertEquals(locked, locked.copy())
        assertEquals(locked.hashCode(), locked.copy().hashCode())
        assertTrue(locked.toString().contains("boom"))
        assertNotEquals(locked, locked.copy(error = null))
        // Two locks with the same error but a bumped nonce are distinct, so a retry still re-renders.
        assertNotEquals(locked, locked.copy(nonce = 4))
        assertNotEquals<AppLockUiState>(locked, AppLockUiState.Unlocked)
    }

    @Test
    fun `Locked defaults to no error and a zero nonce`() {
        assertEquals(AppLockUiState.Locked(), AppLockUiState.Locked(error = null, nonce = 0))
    }

    @Test
    fun `the transient states are singletons`() {
        assertSame(AppLockUiState.Checking, AppLockUiState.Checking)
        assertSame(AppLockUiState.Unlocked, AppLockUiState.Unlocked)
        assertNotEquals<AppLockUiState>(AppLockUiState.Checking, AppLockUiState.Unlocked)
    }
}
