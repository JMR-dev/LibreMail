// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.security

/** What the app-lock gate should do when a foreground pass reconciles settings with device state. */
enum class LockAction {
    /** App-lock is off; show the app without gating. */
    PROCEED,

    /** Prompt the user to authenticate, then unwrap (or first-time arm) the passphrase. */
    REQUIRE_AUTH,

    /**
     * The auth-bound key was invalidated (new biometric enrollment) but the device is still secure
     * and an encrypted cache sealed by that key exists: the cache is unrecoverable, so wipe it and
     * re-sync, then require a fresh authentication which re-arms the key.
     */
    CLEAR_AND_REQUIRE_AUTH,

    /**
     * The device no longer has a secure lock and there is nothing encrypted to protect — the
     * encrypted-cache setting is off AND no auth-sealed passphrase exists: silently disable
     * app-lock (there is no key to authenticate against) and proceed.
     */
    DISABLE_APP_LOCK,

    /**
     * The device no longer has a secure lock but an encrypted cache sealed by the (now invalidated)
     * auth-bound key exists: the cache is unrecoverable, so wipe it and re-sync, then disable
     * app-lock and proceed. The cache re-encrypts under a fresh, non-auth key or stays plaintext.
     */
    CLEAR_AND_DISABLE,
}

/**
 * Pure decision table reconciling the app-lock / encrypted-cache state with the current device
 * security state and Keystore key validity. Extracted from any Android or crypto dependency so the
 * (security-critical) branch logic — in particular the "clear + re-sync, never corrupt" handling of
 * lock removal and biometric re-enrollment — is fully unit-tested.
 */
object KeyInvalidationPolicy {

    /**
     * [encryptedCacheProtected] must reflect the ACTUAL protection state, not the `encryptCache`
     * setting alone: the setting is written immediately but the on-disk conversion is deferred to
     * the next cold start, so an auth-sealed (still encrypted) cache can outlive the setting being
     * off. Callers derive it as `encryptCache setting || an auth-sealed passphrase exists`
     * (issue #479); feeding the raw setting here silently disabled app-lock during that
     * transitional window and stranded a permanently unreadable cache.
     */
    fun decide(
        appLockEnabled: Boolean,
        encryptedCacheProtected: Boolean,
        deviceSecure: Boolean,
        keyInvalidated: Boolean,
    ): LockAction = when {
        !appLockEnabled -> LockAction.PROCEED
        !deviceSecure ->
            if (encryptedCacheProtected) LockAction.CLEAR_AND_DISABLE else LockAction.DISABLE_APP_LOCK
        keyInvalidated ->
            if (encryptedCacheProtected) LockAction.CLEAR_AND_REQUIRE_AUTH else LockAction.REQUIRE_AUTH
        else -> LockAction.REQUIRE_AUTH
    }
}
