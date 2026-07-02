// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.power

import kotlinx.coroutines.flow.Flow

/** Point-in-time battery snapshot: charge [percent] (0–100) and whether the device is on power. */
data class BatteryStatus(val percent: Int, val isCharging: Boolean) {
    companion object {
        /**
         * Fallback when the platform can't report battery state (missing service or extras): a full,
         * discharging battery, so every battery gate fails open to normal behaviour — bad data can
         * never pause sync or push.
         */
        val ASSUME_OK = BatteryStatus(percent = 100, isCharging = false)
    }
}

/**
 * Battery-state source for the resource-aware sync decisions in
 * [org.libremail.data.sync.SyncResourcePolicy]. An interface so those call sites stay unit-testable;
 * the live Android implementation is [AndroidBatteryStatusProvider].
 */
interface BatteryStatusProvider {
    /** One-shot snapshot, for point-in-time decisions (e.g. "prefetch content after this sync?"). */
    fun current(): BatteryStatus

    /**
     * Stream of snapshots for long-lived consumers (the IDLE push service). Emits the current state
     * immediately on collection, then on every battery change, deduplicated.
     */
    fun status(): Flow<BatteryStatus>
}
