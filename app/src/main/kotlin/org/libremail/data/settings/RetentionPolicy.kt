// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.settings

import java.time.Instant
import java.time.ZoneOffset

/**
 * The effective device-only retention limits for one account (issue #13), after resolving its
 * per-account overrides against the global default. `0` in either dimension means "unlimited" (keep
 * everything for that dimension). Both limits are independent ceilings: a message is kept only if it
 * is within the newest [count] of its folder AND newer than the [months] age cutoff; violating either
 * makes it prunable. The retention floor for backfill is therefore whichever limit is hit first.
 *
 * Retention is enforced purely on the device — the server copy is never deleted.
 */
data class RetentionPolicy(val count: Int, val months: Int) {

    /** True when nothing is limited, so no pruning runs and backfill may page a folder to the very end. */
    val isUnlimited: Boolean get() = count <= 0 && months <= 0

    /** The count ceiling, or null when unlimited. */
    val countLimit: Int? get() = count.takeIf { it > 0 }

    /**
     * Epoch-millis age cutoff derived from [months]: messages strictly older than this are prunable.
     * Null when the age limit is unlimited. Uses calendar months (UTC) so "3 months" tracks the
     * calendar rather than a fixed 30-day approximation.
     */
    fun ageCutoffMillis(nowMillis: Long): Long? = months.takeIf { it > 0 }?.let {
        Instant.ofEpochMilli(nowMillis)
            .atZone(ZoneOffset.UTC)
            .minusMonths(it.toLong())
            .toInstant()
            .toEpochMilli()
    }

    companion object {
        /** The default policy: keep everything on device (matches #12's fetch-all default). */
        val KEEP_EVERYTHING = RetentionPolicy(count = 0, months = 0)

        /**
         * Resolves the effective policy for an account: a non-null per-account override wins per
         * dimension, otherwise the global default applies. Negative inputs are clamped to 0 (unlimited).
         */
        fun resolve(accountCount: Int?, accountMonths: Int?, defaultCount: Int, defaultMonths: Int): RetentionPolicy =
            RetentionPolicy(
                count = (accountCount ?: defaultCount).coerceAtLeast(0),
                months = (accountMonths ?: defaultMonths).coerceAtLeast(0),
            )
    }
}
