// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import org.libremail.reporting.AppLog
import org.libremail.reporting.accountLogRef
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-account, per-day running total of bytes downloaded by background prefetch, tracked against
 * Gmail's documented [GmailSyncLimits.DAILY_DOWNLOAD_BUDGET_BYTES] (issue #361). The stateful
 * counterpart to the pure [GmailSyncLimits]:
 * [org.libremail.data.repository.MailRepositoryImpl.prefetchMessage] feeds it bytes actually pulled
 * over the network via [recordDownload], and [MailBackfiller] / [MailSyncer] consult
 * [isOverDailyBudget] before starting a fresh prefetch batch for an account, deferring the rest of the
 * day's prefetch once the budget is reached.
 *
 * **Proactive, not reactive — orthogonal to [AccountThrottleGate].** The #360 gate only fires once a
 * provider actually rejects a request; this tracker heads that off by pacing our OWN traffic against a
 * budget Gmail documents but does not necessarily announce hitting. It has the same relationship to
 * [AccountThrottleGate] that [InteractiveImapGate] already documents having with it: a separate,
 * composing mechanism, not a duplicate or a replacement.
 *
 * **Self-healing.** A day boundary (a wall-clock day number derived from [nowMillis]) resets an
 * account's tracked total, so a deferred account automatically resumes full prefetch the next day with
 * no explicit reset needed — mirrors how [AccountThrottleGate]'s backoff window elapses on its own.
 *
 * **Interactive traffic is deliberately NOT tracked here.** Only background prefetch (issue #361's
 * "sync/backfill" scope) feeds this tracker — opening a message, downloading a tapped attachment, and
 * loading inline images are never deferred by a budget (the same interactive-priority principle
 * #355/#360 already apply), so counting their bytes here would only make the tracker's *deferral*
 * decision — which exclusively affects background prefetch — less representative of what it can
 * actually still influence.
 *
 * State lives only in-process (a `@Singleton`); a process restart clears it, which simply means a
 * fresh process re-earns its budget for the (partial) remainder of the day — a conservative direction
 * to fail in, same as [AccountThrottleGate]'s reset-on-restart. Every log line is PII-free:
 * [accountLogRef] for the account, and byte counts only.
 */
@Singleton
class GmailBandwidthTracker internal constructor(private val nowMillis: () -> Long) {
    /** Production wiring: the real wall clock. */
    @Inject constructor() : this(nowMillis = System::currentTimeMillis)

    /** One account's running download total for [dayEpoch] (whole days since the epoch). */
    private data class Window(val dayEpoch: Long, val bytes: Long)

    private val windows = ConcurrentHashMap<String, Window>()

    /**
     * Adds [bytes] to [accountId]'s running total for today, starting a fresh window if the day has
     * rolled over since the last call (yesterday's total is simply discarded, not carried forward). A
     * no-op for `bytes <= 0`. Logs once, PII-free, the moment this call carries the account from under
     * budget to at-or-over it — not on every call, so an account that stays over budget for the rest of
     * a sync/backfill pass doesn't spam the log.
     */
    fun recordDownload(accountId: String, bytes: Long) {
        if (bytes <= 0L) return
        val day = currentDayEpoch()
        val before = windows[accountId]?.takeIf { it.dayEpoch == day }?.bytes ?: 0L
        val updated = windows.compute(accountId) { _, previous ->
            val carried = if (previous != null && previous.dayEpoch == day) previous.bytes else 0L
            Window(day, carried + bytes)
        }!!
        val budget = GmailSyncLimits.DAILY_DOWNLOAD_BUDGET_BYTES
        if (before < budget && updated.bytes >= budget) {
            AppLog.w(TAG, "daily download budget reached ${accountLogRef(accountId)}")
        }
    }

    /** [accountId]'s tracked download bytes so far today, or 0 when untracked or the day has rolled over. */
    fun bytesDownloadedToday(accountId: String): Long {
        val window = windows[accountId] ?: return 0L
        return if (window.dayEpoch == currentDayEpoch()) window.bytes else 0L
    }

    /** True once [accountId]'s tracked downloads for today reach [GmailSyncLimits.DAILY_DOWNLOAD_BUDGET_BYTES]. */
    fun isOverDailyBudget(accountId: String): Boolean =
        bytesDownloadedToday(accountId) >= GmailSyncLimits.DAILY_DOWNLOAD_BUDGET_BYTES

    private fun currentDayEpoch(): Long = nowMillis() / MILLIS_PER_DAY

    private companion object {
        const val TAG = "GmailBandwidth"
        const val MILLIS_PER_DAY = 24 * 60 * 60 * 1000L
    }
}
