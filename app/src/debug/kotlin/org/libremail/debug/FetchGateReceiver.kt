// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.libremail.data.sync.DebugFetchGate
import org.libremail.data.sync.FetchScope
import org.libremail.reporting.AppLog

/**
 * Debug-only [BroadcastReceiver] (issue #393) that lets an adb-driven perf harness pause/resume the
 * proactive-fetch activities tracked by [DebugFetchGate], so a genuinely uncached message-open can be
 * measured (add an account, let headers sync, then open a message that must hit the network instead of a
 * warmed cache). Declared **only** in `app/src/debug/AndroidManifest.xml`, so it is physically absent
 * from every release APK — the same source-set guarantee `ColdOpenCacheProbe` (#221) relies on.
 *
 * Driven by (component targeted with `-n`, so it needs no `<intent-filter>`):
 * ```
 * adb shell am broadcast -a org.libremail.debug.FETCH_GATE \
 *   -n org.libremail.app/org.libremail.debug.FetchGateReceiver \
 *   --es action <pause|resume|query> --es scope <backfill,prefetch|all>
 * ```
 * `am broadcast` delivers this **ordered**, so the receiver returns the resulting state as result data
 * (e.g. `data=paused=[backfill,prefetch]`) which `am` prints — a synchronous, race-free read-back for
 * the harness. A `query` reports the current state without changing it. The new state is logged via the
 * PII-free [AppLog] (scope names only — never an email, host, or message content).
 */
class FetchGateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra(EXTRA_ACTION)?.trim()?.lowercase()
        val scopes = FetchScope.parse(intent.getStringExtra(EXTRA_SCOPE))
        when (action) {
            ACTION_PAUSE -> {
                DebugFetchGate.pause(scopes)
                AppLog.i(TAG, "fetch gate pause -> ${DebugFetchGate.pausedResult()}")
            }
            ACTION_RESUME -> {
                DebugFetchGate.resume(scopes)
                AppLog.i(TAG, "fetch gate resume -> ${DebugFetchGate.pausedResult()}")
            }
            ACTION_QUERY -> AppLog.i(TAG, "fetch gate query -> ${DebugFetchGate.pausedResult()}")
            else -> AppLog.w(TAG, "fetch gate: unknown action")
        }
        // Return the gate state as ordered-broadcast result data for a synchronous read-back. Guarded so
        // a non-ordered send (which has no result receiver) can't crash the receiver.
        if (isOrderedBroadcast) {
            resultCode = RESULT_CODE
            resultData = DebugFetchGate.pausedResult()
        }
    }

    companion object {
        /** The broadcast action the harness sends (kept for parity with the adb command; delivery is by `-n`). */
        const val ACTION = "org.libremail.debug.FETCH_GATE"

        /** `--es action <pause|resume|query>`. */
        const val EXTRA_ACTION = "action"

        /** `--es scope <comma-list|all>` (see [FetchScope.parse]). */
        const val EXTRA_SCOPE = "scope"

        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_QUERY = "query"

        private const val TAG = "FetchGateReceiver"
        private const val RESULT_CODE = 0
    }
}
