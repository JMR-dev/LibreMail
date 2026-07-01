// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.security

/** Whether the app UI is currently gated behind the screen-lock. */
enum class LockState { LOCKED, UNLOCKED }

/**
 * Pure state machine for the app-lock UI gate. Decides when the lock screen must be shown from
 * foreground/background transitions plus an inactivity grace period. It has no Android or crypto
 * dependencies, so the (security-critical) gating logic is exercised entirely by JVM unit tests.
 *
 * Rules:
 *  - App-lock disabled: always [LockState.UNLOCKED].
 *  - Cold start with app-lock on: starts [LockState.LOCKED] until [onAuthenticated].
 *  - Returning to the foreground within [graceMillis] of backgrounding stays unlocked; after the
 *    grace period it re-locks. Foregrounding without a preceding background (e.g. a configuration
 *    change / rotation) does not re-lock an already-unlocked session.
 */
class AppLockGate(private val graceMillis: Long = DEFAULT_GRACE_MILLIS) {

    var state: LockState = LockState.LOCKED
        private set

    private var backgroundedAt: Long? = null

    /**
     * Recompute the lock state when the app comes to the foreground. [now] is a monotonic-ish epoch
     * in milliseconds. Returns the resulting state.
     */
    fun onForeground(now: Long, appLockEnabled: Boolean): LockState {
        state = when {
            !appLockEnabled -> LockState.UNLOCKED
            state == LockState.LOCKED -> LockState.LOCKED
            backgroundedAt == null -> LockState.UNLOCKED
            withinGrace(now) -> LockState.UNLOCKED
            else -> LockState.LOCKED
        }
        backgroundedAt = null
        return state
    }

    /** Record the time the app was backgrounded, to evaluate the grace period on return. */
    fun onBackground(now: Long) {
        backgroundedAt = now
    }

    /** Mark the session unlocked after a successful authentication. */
    fun onAuthenticated() {
        state = LockState.UNLOCKED
        backgroundedAt = null
    }

    /** Force the gate back to locked (e.g. after cache invalidation). */
    fun lock() {
        state = LockState.LOCKED
    }

    private fun withinGrace(now: Long): Boolean {
        val since = backgroundedAt ?: return false
        return now - since in 0..graceMillis
    }

    companion object {
        /** Time the user can leave the app before re-authentication is required. */
        const val DEFAULT_GRACE_MILLIS: Long = 30_000
    }
}
