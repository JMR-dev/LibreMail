// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.security

import org.junit.Test
import kotlin.test.assertEquals

class KeyInvalidationPolicyTest {

    private fun decide(
        appLock: Boolean = true,
        encrypt: Boolean = true,
        secure: Boolean = true,
        invalidated: Boolean = false,
    ) = KeyInvalidationPolicy.decide(appLock, encrypt, secure, invalidated)

    @Test
    fun `app-lock off proceeds`() {
        assertEquals(LockAction.PROCEED, decide(appLock = false))
        assertEquals(LockAction.PROCEED, decide(appLock = false, secure = false, invalidated = true))
    }

    @Test
    fun `healthy secure device with valid key requires auth`() {
        assertEquals(LockAction.REQUIRE_AUTH, decide())
    }

    @Test
    fun `lock removed with encrypted cache clears and disables`() {
        // The auth-bound passphrase is unrecoverable; wipe the cache and drop the gate.
        assertEquals(LockAction.CLEAR_AND_DISABLE, decide(secure = false, encrypt = true))
    }

    @Test
    fun `lock removed without encrypted cache just disables`() {
        assertEquals(LockAction.DISABLE_APP_LOCK, decide(secure = false, encrypt = false))
    }

    @Test
    fun `biometric re-enrollment with encrypted cache clears and re-requires auth`() {
        assertEquals(LockAction.CLEAR_AND_REQUIRE_AUTH, decide(invalidated = true, encrypt = true))
    }

    @Test
    fun `biometric re-enrollment without encrypted cache re-requires auth without clearing`() {
        // Nothing encrypted to lose; a fresh key is minted on the next successful unlock.
        assertEquals(LockAction.REQUIRE_AUTH, decide(invalidated = true, encrypt = false))
    }

    @Test
    fun `lock removal takes precedence over key-invalidation flag`() {
        // Both true: the device being insecure dominates (can't authenticate at all).
        assertEquals(LockAction.CLEAR_AND_DISABLE, decide(secure = false, invalidated = true, encrypt = true))
    }
}
