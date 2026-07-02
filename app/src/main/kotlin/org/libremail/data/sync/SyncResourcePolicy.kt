// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.scan
import org.libremail.data.settings.FetchPolicy
import org.libremail.power.BatteryStatus

/** How new mail is watched for: a live IMAP IDLE connection, or the 15-minute periodic WorkManager sync. */
enum class PushMode { IDLE, POLLING }

/**
 * Pure decisions for how sync adapts to device resource constraints (battery level, charging state,
 * network metering) — shared by the content-prefetch paths (#88, #89: [MailSyncer]'s recent-window
 * prefetch and [MailBackfiller]'s full-history prefetch) and the IMAP IDLE push service (#90).
 * Deliberately free of Android types so it is exhaustively unit-testable; the live inputs come from
 * [org.libremail.power.BatteryStatusProvider] and the shared [isActiveNetworkUnmetered] check.
 *
 * These are runtime gates, not settings writes: nothing here mutates a preference, so every effect
 * self-reverts as soon as the device leaves the constrained state.
 *
 * Charging exempts from every battery gate (#89's open question, resolved): a device on power is not
 * under battery pressure, so a plugged-in phone at 15% keeps prefetching and keeps IDLE alive.
 */
object SyncResourcePolicy {

    /** At or below this charge (and not charging), battery-sensitive work pauses (#89, #90). */
    const val LOW_BATTERY_PERCENT = 20

    /**
     * Once push has fallen back to polling, IDLE resumes only at or above this charge (or on the
     * charger). Strictly above [LOW_BATTERY_PERCENT] so a battery hovering at the threshold cannot
     * flap push on and off (hysteresis).
     */
    const val PUSH_RESUME_PERCENT = 25

    /** True when the battery is at or below [LOW_BATTERY_PERCENT] and the device is not charging. */
    fun isBatteryLow(battery: BatteryStatus): Boolean = !battery.isCharging && battery.percent <= LOW_BATTERY_PERCENT

    /**
     * Whether to aggressively pre-download full message content (bodies + attachments) after a header
     * sync: the user's [FetchPolicy] decides, except at low battery, where the prefetch pauses for
     * every policy (#89). [unmetered] is a supplier because only [FetchPolicy.WIFI_ONLY] needs the
     * network state — the other policies (and any low-battery pause) never query it. Header sync is
     * never gated here — new mail still arrives; a paused prefetch merely defers content caching to
     * the next healthy sync (or to on-demand fetch when a message is opened).
     */
    fun shouldPrefetchContent(policy: FetchPolicy, unmetered: () -> Boolean, battery: BatteryStatus): Boolean {
        if (isBatteryLow(battery)) return false
        return when (policy) {
            FetchPolicy.ALWAYS -> true
            FetchPolicy.WIFI_ONLY -> unmetered()
            FetchPolicy.ON_DEMAND -> false
        }
    }

    /**
     * Next push mode given the latest battery snapshot and the [previous] mode: drop to
     * [PushMode.POLLING] at low battery; return to [PushMode.IDLE] when charging or once charge
     * recovers to [PUSH_RESUME_PERCENT]. Inside the band between the two thresholds the previous mode
     * is kept (hysteresis), so jitter around the cutoff can't flap connections.
     */
    fun pushMode(battery: BatteryStatus, previous: PushMode): PushMode = when {
        battery.isCharging -> PushMode.IDLE
        battery.percent <= LOW_BATTERY_PERCENT -> PushMode.POLLING
        previous == PushMode.POLLING && battery.percent < PUSH_RESUME_PERCENT -> PushMode.POLLING
        else -> PushMode.IDLE
    }

    /**
     * Folds a stream of battery snapshots into the push mode to run, starting from [initial]
     * (assessed with no history, so when collection starts on an already-low battery the very first
     * mode is [PushMode.POLLING]). Deduplicated: changes inside the hysteresis band emit nothing.
     */
    fun pushModes(initial: BatteryStatus, updates: Flow<BatteryStatus>): Flow<PushMode> = updates
        .scan(pushMode(initial, PushMode.IDLE)) { previous, battery -> pushMode(battery, previous) }
        .distinctUntilChanged()
}
