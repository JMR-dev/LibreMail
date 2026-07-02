// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.settings

import kotlinx.coroutines.flow.first
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

/**
 * The effective retention policy for [accountId], resolving its per-account overrides against the
 * global default. Read through the same [AccountSettingsRepository] / [SettingsRepository] every
 * enforcement site uses — the foreground fetch window ([org.libremail.data.sync.MailSyncer]), the
 * backfill floor ([org.libremail.data.sync.MailBackfiller]), and the pruner
 * ([org.libremail.data.sync.MailPruner]) — so none of them can resolve a different floor, the
 * divergence that would otherwise let backfill and prune fight over the same rows.
 */
internal suspend fun AccountSettingsRepository.effectiveRetention(
    settings: SettingsRepository,
    accountId: String,
): RetentionPolicy {
    val account = get(accountId)
    val global = settings.settings.first()
    return RetentionPolicy.resolve(
        accountCount = account.retentionCount,
        accountMonths = account.retentionMonths,
        defaultCount = global.retentionCount,
        defaultMonths = global.retentionMonths,
    )
}
