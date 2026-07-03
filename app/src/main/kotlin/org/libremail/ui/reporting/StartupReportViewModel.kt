// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.reporting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.libremail.reporting.ReportKind
import org.libremail.reporting.ReportStore
import javax.inject.Inject

/**
 * Surfaces a pending crash report (if any) so the app can offer it for review on launch. The prompt is
 * gated (see #255) so it fires at most once, only for a legitimate recent crash:
 *
 * - **First re-open only:** a report is auto-offered once, then persistently marked surfaced; it never
 *   re-nags on later launches. It stays in the store (still listed under Problem Reports for manual
 *   review); only [discard] deletes it.
 * - **< 24h only:** older crashes are never auto-surfaced (they may still be reviewed manually).
 * - **Legitimate crash only:** only `CrashReporter`'s uncaught-exception handler ever creates a
 *   [ReportKind.CRASH] report, so an app update, a user-initiated close, or a force-stop create no
 *   report and therefore never pop this prompt.
 *
 * @param now clock provider, injected so the age gate is unit-testable.
 */
@HiltViewModel
class StartupReportViewModel(private val store: ReportStore, private val now: () -> Long) : ViewModel() {

    @Inject
    constructor(store: ReportStore) : this(store, { System.currentTimeMillis() })

    val pendingCrash: StateFlow<ReportSummary?> =
        store.reports.map { reports ->
            reports.firstOrNull { report ->
                report.kind == ReportKind.CRASH &&
                    !report.surfaced &&
                    report.createdAtMillis >= now() - CRASH_MAX_AGE_MS
            }?.let { ReportSummary(it.id, it.kind, it.createdAtMillis) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_MS), null)

    /**
     * "Not now" / "Review": persistently marks the crash surfaced so it is auto-offered at most once
     * across launches. The report stays saved (still shown in Problem Reports); only [discard] deletes.
     */
    fun dismiss(id: String) {
        viewModelScope.launch { store.markSurfaced(id) }
    }

    fun discard(id: String) {
        viewModelScope.launch { store.delete(id) }
    }

    private companion object {
        const val SUBSCRIBE_MS = 5_000L
        const val CRASH_MAX_AGE_MS = 24L * 60 * 60 * 1000
    }
}
