// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import kotlinx.coroutines.delay
import org.libremail.reporting.AppLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Proactive pacing for the full-history backfill (issue #356): the single place that bounds how hard one
 * [BackfillWorker] run drives [MailBackfiller], so background backfill fills history *steadily* instead of
 * running flat-out and keeping the account's IMAP session saturated for a whole session (the 2026-07-05
 * on-device finding: a large mailbox reports `moreWork=true` forever, so the worker's slice-chaining loop
 * never idles).
 *
 * Two levers, both deliberately gentle and configurable:
 *  - **Inter-slice cooldown.** A fixed [cooldownMillis] idle *between* chained slices, so even within a run
 *    backfill breathes and leaves the account headroom rather than paging back-to-back.
 *  - **Per-run slice cap.** At most [maxSlicesPerRun] slices per run; once hit, the run ends and defers to
 *    the 30-min periodic cadence (and `backfillNow()`), so one run can never monopolise the account for the
 *    whole session.
 *
 * This is the *proactive* counterpart to the two *reactive* mechanisms it composes with — it does not
 * duplicate or fight them:
 *  - #355 ([InteractiveImapGate]): while an interactive, user-facing fetch is in flight, the *next* slice
 *    already parks at [MailBackfiller.yieldToInteractive]'s per-page yield point. Stacking a fixed cooldown
 *    on top of that park would only double the idle for no gain, so the cooldown is **skipped** whenever an
 *    interactive fetch is active and the park is left as the sole backpressure — exactly one mechanism gates
 *    any given gap (no pathological double-delay).
 *  - #360 ([AccountThrottleGate]): a slice whose only outstanding work is a throttled account reports
 *    `moreWork=false`, so [runPaced] stops and no cooldown is burned spinning on a backed-off provider; the
 *    provider backoff window elapses on its own and a later scheduled run resumes.
 *
 * The cooldown is a plain cancellable [delay] and [runPaced] rechecks its `shouldContinue` predicate (the
 * worker's `!isStopped`) before every slice, so a WorkManager stop / teardown ends a run promptly — the
 * cooldown never blocks cancellation. Every breadcrumb is PII-free: durations and counts only.
 */
@Singleton
class BackfillPacer internal constructor(
    private val interactiveGate: InteractiveImapGate,
    private val cooldownMillis: Long,
    private val maxSlicesPerRun: Int,
) {
    /** Production wiring: the tuned steady-state cooldown and per-run cap. */
    @Inject
    constructor(interactiveGate: InteractiveImapGate) :
        this(interactiveGate, INTER_SLICE_COOLDOWN_MS, MAX_SLICES_PER_RUN)

    /**
     * Drives one worker run's worth of paced backfill. Runs [slice] — one bounded backfill slice, returning
     * `true` while an immediate follow-up has more work — repeatedly while [shouldContinue] holds, cooling
     * down between slices ([coolDownBetweenSlices]) and stopping after [maxSlicesPerRun] slices. Returns
     * whether history still has more to fill (informational; a capped or stopped run is resumed by the
     * periodic cadence). Cancellation propagates out of the cooldown so a stop ends the run at once.
     */
    suspend fun runPaced(shouldContinue: () -> Boolean, slice: suspend () -> Boolean): Boolean {
        var slices = 0
        while (shouldContinue()) {
            val moreWork = slice()
            slices++
            if (!moreWork) return false
            if (slices >= maxSlicesPerRun) {
                AppLog.i(TAG, "backfill run capped at $maxSlicesPerRun slice(s); deferring to periodic cadence")
                return true
            }
            coolDownBetweenSlices()
        }
        return true
    }

    /**
     * Idles [cooldownMillis] before the next slice — unless an interactive fetch is active (#355), in which
     * case the cooldown is skipped and the next slice's per-page park provides the backpressure, so the two
     * mechanisms never stack into a double delay.
     */
    private suspend fun coolDownBetweenSlices() {
        if (interactiveGate.isInteractiveActive()) {
            AppLog.i(TAG, "backfill cooldown skipped: interactive fetch in flight")
            return
        }
        AppLog.i(TAG, "backfill cooldown ${cooldownMillis}ms before next slice")
        delay(cooldownMillis)
    }

    companion object {
        private const val TAG = "BackfillPacer"

        /**
         * Fixed idle between chained slices. Gentle relative to a ~85 s slice (the on-device measurement)
         * yet long enough to give an interactive open a clear, backfill-free window — on top of #355's park.
         * `internal` so the worker test can assert the production cadence without a magic number.
         */
        internal const val INTER_SLICE_COOLDOWN_MS = 30_000L

        /**
         * Slices one run may chain before deferring to the periodic cadence. Bounds a run to a few minutes
         * (a handful of ~85 s slices plus their cooldowns) — comfortably under WorkManager's ~10-min
         * execution window — so a big mailbox fills over many short runs instead of one endless session.
         * `internal` so the worker test can assert the cap without a magic number.
         */
        internal const val MAX_SLICES_PER_RUN = 4
    }
}
