// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.reporting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.libremail.reporting.ReportKind
import org.libremail.reporting.ReportStore
import javax.inject.Inject

/** Surfaces a pending crash report (if any) so the app can offer it for review on launch. */
@HiltViewModel
class StartupReportViewModel @Inject constructor(private val store: ReportStore) : ViewModel() {

    private val dismissed = MutableStateFlow(false)

    val pendingCrash: StateFlow<ReportSummary?> =
        combine(store.reports, dismissed) { reports, isDismissed ->
            if (isDismissed) {
                null
            } else {
                reports.firstOrNull { it.kind == ReportKind.CRASH }
                    ?.let { ReportSummary(it.id, it.kind, it.createdAtMillis) }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_MS), null)

    /** Hides the prompt for this launch; the report stays saved and is offered again next launch. */
    fun dismiss() {
        dismissed.value = true
    }

    fun discard(id: String) {
        dismissed.value = true
        viewModelScope.launch { store.delete(id) }
    }

    private companion object {
        const val SUBSCRIBE_MS = 5_000L
    }
}
