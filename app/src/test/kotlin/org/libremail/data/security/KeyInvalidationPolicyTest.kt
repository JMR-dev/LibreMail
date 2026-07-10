// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.security

import org.junit.Test
import kotlin.test.assertEquals

class KeyInvalidationPolicyTest {

    // `cacheProtected` is decide()'s `encryptedCacheProtected` input: derived by callers as
    // "encryptCache setting ON, or an auth-sealed passphrase still exists" (issue #479) — never the
    // raw setting, which can already be off while the on-disk cache is still auth-sealed.
    private fun decide(
        appLock: Boolean = true,
        cacheProtected: Boolean = true,
        secure: Boolean = true,
        invalidated: Boolean = false,
    ) = KeyInvalidationPolicy.decide(appLock, cacheProtected, secure, invalidated)

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
    fun `lock removed with a protected cache clears and disables`() {
        // The auth-bound passphrase is unrecoverable; wipe the cache and drop the gate.
        assertEquals(LockAction.CLEAR_AND_DISABLE, decide(secure = false, cacheProtected = true))
    }

    @Test
    fun `lock removed while the cache is still auth-sealed clears even with the setting off`() {
        // The issue-#479 transitional window: encryptCache was just toggled OFF (setting written,
        // on-disk decrypt deferred to the next cold start, SEALED_AUTH still present) and the user
        // removes the device lock. The caller derives cacheProtected from setting-OR-seal, so this
        // state lands on the same protected row — CLEAR_AND_DISABLE — never DISABLE_APP_LOCK, which
        // would strand a permanently unreadable cache behind a gate that no longer authenticates.
        assertEquals(LockAction.CLEAR_AND_DISABLE, decide(secure = false, cacheProtected = true, invalidated = true))
    }

    @Test
    fun `lock removed without a protected cache just disables`() {
        assertEquals(LockAction.DISABLE_APP_LOCK, decide(secure = false, cacheProtected = false))
    }

    @Test
    fun `biometric re-enrollment with a protected cache clears and re-requires auth`() {
        assertEquals(LockAction.CLEAR_AND_REQUIRE_AUTH, decide(invalidated = true, cacheProtected = true))
    }

    @Test
    fun `biometric re-enrollment without a protected cache re-requires auth without clearing`() {
        // Nothing encrypted to lose; a fresh key is minted on the next successful unlock.
        assertEquals(LockAction.REQUIRE_AUTH, decide(invalidated = true, cacheProtected = false))
    }

    @Test
    fun `lock removal takes precedence over key-invalidation flag`() {
        // Both true: the device being insecure dominates (can't authenticate at all).
        assertEquals(LockAction.CLEAR_AND_DISABLE, decide(secure = false, invalidated = true, cacheProtected = true))
    }

    @Test
    fun `every one of the 16 input combinations maps to its pinned action`() {
        // The complete truth table for decide(appLock, cacheProtected, secure, invalidated): all
        // 2^4 = 16 rows listed explicitly, so a mutation of ANY branch is caught — most importantly
        // the common (on, *, secure, valid) rows, whose silent flip to PROCEED would be a lock
        // bypass. The completeness guard below fails if a row is ever dropped, keeping the table
        // exhaustive. `cacheProtected` is setting-OR-seal (issue #479), so the "protected" rows also
        // pin the transitional encryptCache-off-but-still-auth-sealed states.
        //
        // Columns: appLock, cacheProtected, secure, invalidated -> expected action.
        val table = listOf(
            // App-lock OFF: always PROCEED, whatever the other three inputs are.
            Case(false, false, false, false, LockAction.PROCEED),
            Case(false, false, false, true, LockAction.PROCEED),
            Case(false, false, true, false, LockAction.PROCEED),
            Case(false, false, true, true, LockAction.PROCEED),
            Case(false, true, false, false, LockAction.PROCEED),
            Case(false, true, false, true, LockAction.PROCEED),
            Case(false, true, true, false, LockAction.PROCEED),
            Case(false, true, true, true, LockAction.PROCEED),
            // App-lock ON, device NOT secure (lock removed): clear+disable iff a cache is protected
            // (setting on OR still auth-sealed), else disable.
            Case(true, true, false, false, LockAction.CLEAR_AND_DISABLE),
            Case(true, true, false, true, LockAction.CLEAR_AND_DISABLE),
            Case(true, false, false, false, LockAction.DISABLE_APP_LOCK),
            Case(true, false, false, true, LockAction.DISABLE_APP_LOCK),
            // App-lock ON, secure, key invalidated: clear+re-auth iff a cache is protected, else just re-auth.
            Case(true, true, true, true, LockAction.CLEAR_AND_REQUIRE_AUTH),
            Case(true, false, true, true, LockAction.REQUIRE_AUTH),
            // App-lock ON, secure, key valid: the common case — require auth, no wipe.
            Case(true, true, true, false, LockAction.REQUIRE_AUTH),
            Case(true, false, true, false, LockAction.REQUIRE_AUTH),
        )

        // Exhaustiveness: exactly the 16 distinct (appLock, cacheProtected, secure, invalidated) combinations.
        assertEquals(16, table.size, "the table must list all 2^4 input combinations")
        assertEquals(
            16,
            table.map { listOf(it.appLock, it.cacheProtected, it.secure, it.invalidated) }.toSet().size,
            "every row must be a distinct input combination",
        )

        for (case in table) {
            assertEquals(
                case.expected,
                decide(case.appLock, case.cacheProtected, case.secure, case.invalidated),
                "decide(appLock=${case.appLock}, cacheProtected=${case.cacheProtected}, " +
                    "secure=${case.secure}, invalidated=${case.invalidated})",
            )
        }
    }

    private data class Case(
        val appLock: Boolean,
        val cacheProtected: Boolean,
        val secure: Boolean,
        val invalidated: Boolean,
        val expected: LockAction,
    )
}
