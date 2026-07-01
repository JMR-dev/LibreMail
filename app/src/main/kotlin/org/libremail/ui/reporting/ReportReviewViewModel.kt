// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.reporting

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.libremail.reporting.ReportStore
import org.libremail.reporting.ReportSubmitter
import org.libremail.reporting.SubmitStatus
import org.libremail.ui.navigation.Routes
import javax.inject.Inject

/** UI-facing status of a submission attempt. [UNAVAILABLE] means no endpoint is configured. */
enum class SubmitUiState { IDLE, SUBMITTING, SUCCEEDED, FAILED, UNAVAILABLE }

data class ReportReviewState(
    val loaded: Boolean = false,
    val exists: Boolean = false,
    val payload: String = "",
    val comment: String = "",
    val canSubmitOnline: Boolean = false,
    val submit: SubmitUiState = SubmitUiState.IDLE,
)

@HiltViewModel
class ReportReviewViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val store: ReportStore,
    private val submitter: ReportSubmitter,
) : ViewModel() {

    private val reportId: String = checkNotNull(savedStateHandle[Routes.REPORT_REVIEW_ARG_ID])
    private val comment = MutableStateFlow(store.find(reportId)?.userComment.orEmpty())
    private val submitState = MutableStateFlow(SubmitUiState.IDLE)

    val state: StateFlow<ReportReviewState> =
        combine(store.reports, comment, submitState) { reports, currentComment, submit ->
            val report = reports.firstOrNull { it.id == reportId }
            ReportReviewState(
                loaded = true,
                exists = report != null,
                // The comment is folded in so the preview is byte-for-byte what a submit would send.
                payload = report?.copy(userComment = currentComment)?.toSubmissionPayload().orEmpty(),
                comment = currentComment,
                canSubmitOnline = submitter.isEnabled,
                submit = submit,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_MS), ReportReviewState())

    fun updateComment(value: String) {
        comment.value = value
    }

    fun discard() {
        viewModelScope.launch { store.delete(reportId) }
    }

    /**
     * The only path that can send a report off-device, and only from an explicit Submit tap. Persists
     * the reviewed comment first so the upload matches exactly what was shown, then enqueues the
     * worker (unless no endpoint is configured, in which case it steers the user to Copy/Save).
     */
    fun submit() {
        viewModelScope.launch {
            val report = store.find(reportId) ?: return@launch
            store.save(report.copy(userComment = comment.value))
            if (!submitter.isEnabled) {
                submitState.value = SubmitUiState.UNAVAILABLE
                return@launch
            }
            submitter.submit(reportId)
            submitState.value = SubmitUiState.SUBMITTING
            submitter.status(reportId).collect { submitState.value = it.toUi() }
        }
    }

    /** The exact text shown for review — used for Copy and Save-to-file. */
    fun payload(): String = store.find(reportId)?.copy(userComment = comment.value)?.toSubmissionPayload().orEmpty()

    private fun SubmitStatus.toUi(): SubmitUiState = when (this) {
        SubmitStatus.IDLE, SubmitStatus.SUBMITTING -> SubmitUiState.SUBMITTING
        SubmitStatus.SUCCEEDED -> SubmitUiState.SUCCEEDED
        SubmitStatus.FAILED -> SubmitUiState.FAILED
    }

    private companion object {
        const val SUBSCRIBE_MS = 5_000L
    }
}
