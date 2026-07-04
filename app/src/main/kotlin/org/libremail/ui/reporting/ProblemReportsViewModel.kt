// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.reporting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.libremail.reporting.DebugReport
import org.libremail.reporting.DiagnosticsCollector
import org.libremail.reporting.ReportKind
import org.libremail.reporting.ReportStore
import javax.inject.Inject

/** A row in the problem-reports list. */
data class ReportSummary(val id: String, val kind: ReportKind, val createdAtMillis: Long)

@HiltViewModel
class ProblemReportsViewModel @Inject constructor(
    private val store: ReportStore,
    private val collector: DiagnosticsCollector,
) : ViewModel() {

    val reports: StateFlow<List<ReportSummary>> = store.reports
        .map { list -> list.map { it.toSummary() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_MS), emptyList())

    // Emits the id of a freshly created report so the screen can open it for review immediately.
    private val _created = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val created: SharedFlow<String> = _created

    /** True while a manual report is being collected; gates the button against a double-tap (#304). */
    private val _creating = MutableStateFlow(false)
    val creating: StateFlow<Boolean> = _creating.asStateFlow()

    fun createManualReport() {
        // Flip [_creating] synchronously before the launch so a double-tap can't collect and save two
        // reports (and emit two "open review" navigations). Reset on completion — unlike a save-and-pop
        // screen, this list stays put, so the user may legitimately create another later (#304).
        if (_creating.value) return
        _creating.value = true
        viewModelScope.launch {
            try {
                val report = collector.collectManual()
                store.save(report)
                _created.tryEmit(report.id)
            } finally {
                _creating.value = false
            }
        }
    }

    fun discard(id: String) {
        viewModelScope.launch { store.delete(id) }
    }

    private fun DebugReport.toSummary() = ReportSummary(id, kind, createdAtMillis)

    private companion object {
        const val SUBSCRIBE_MS = 5_000L
    }
}
