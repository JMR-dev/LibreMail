// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.push

import android.app.Service

/**
 * The dataSync foreground-start decision for [IdleService.onStartCommand] (#354), pulled out of the
 * Android [Service] so it is JVM-unit-testable without Robolectric (which this repo does not use).
 *
 * After the Android 14+ dataSync FGS runtime cap fires (#302) and [IdleService] self-stops, the
 * service is (re)started — the app deterministically restarts it via
 * `LibreMailApplication.ensurePushStarted()`, and previously the platform also auto-restarted it via
 * `START_STICKY` with a null intent. Each restart re-entered `onStartCommand` and unconditionally
 * called `startForeground(..., dataSync)` while the rolling-24h budget was still exhausted, so the
 * platform rejected it with `ForegroundServiceStartNotAllowedException` (uncaught → crash → sticky
 * restart → loop). This seam encodes the fix: skip the start outright while still inside the cap
 * window, otherwise attempt it and route the rejection — a `ForegroundServiceStartNotAllowedException`
 * (API 31+), caught here via its [IllegalStateException] supertype so no `minSdk`-29 class load is
 * needed — into a clean degrade instead of letting it propagate. Any other throwable propagates.
 */
internal object IdleForegroundStarter {

    /**
     * Enters dataSync foreground state, or degrades to the 15-minute periodic-sync fallback when that
     * start is — or would be — illegal.
     *
     * @param capActive true while still within the runtime-cap window recorded at the last cap event;
     *   the start is then skipped without attempting it (and without a cause).
     * @param enterForeground the real `ServiceCompat.startForeground(..., dataSync)` call; may throw
     *   `ForegroundServiceStartNotAllowedException` (an [IllegalStateException]) when the runtime cap is
     *   exhausted or the start raced into the background.
     * @param onStarted run after a successful foreground start (proceed with IDLE watchers).
     * @param onDegraded run when the start was skipped ([capActive]) or rejected; receives the
     *   rejection cause, or `null` for the cap-window skip. Must schedule periodic sync and stop the
     *   service (it was started via `startForegroundService`, so it must stop promptly to avoid the
     *   "did not call startForeground in time" ANR).
     * @return the value `onStartCommand` should return — always [Service.START_NOT_STICKY]: push is
     *   app-managed, so the platform's sticky null-intent auto-restart is redundant and fires exactly
     *   in the states that cannot legally start a dataSync FGS.
     */
    fun startForegroundOrDegrade(
        capActive: Boolean,
        enterForeground: () -> Unit,
        onStarted: () -> Unit,
        onDegraded: (cause: Throwable?) -> Unit,
    ): Int {
        when {
            capActive -> onDegraded(null)
            else -> {
                val entered = try {
                    enterForeground()
                    true
                } catch (rejected: IllegalStateException) {
                    // ForegroundServiceStartNotAllowedException (API 31+) extends IllegalStateException;
                    // catching the supertype funnels the runtime-cap/background rejection into the
                    // degrade path without a version gate, instead of crash-looping (#354).
                    onDegraded(rejected)
                    false
                }
                if (entered) onStarted()
            }
        }
        return Service.START_NOT_STICKY
    }
}
