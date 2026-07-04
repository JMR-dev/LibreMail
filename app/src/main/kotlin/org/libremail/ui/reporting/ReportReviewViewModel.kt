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

/**
 * Pure validation rules for problem-report submission (#159). Kept dependency-free — in particular,
 * no `android.util.Patterns`, which is a no-op stub under plain JVM unit tests — so both the
 * min-length and email-shape checks are directly unit-testable and shareable between
 * [ReportReviewState] and [ReportReviewViewModel].
 */
object ReportSubmissionRules {
    /** Minimum comment length (characters) required before Submit is enabled. */
    const val MIN_COMMENT_LENGTH = 200

    // Basic shape only: a non-blank local part, an `@`, and a domain with at least one `.` and
    // non-blank labels either side of it. Deliberately not a full RFC 5322 validator.
    private val EMAIL_REGEX = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

    /** Whether [comment] reaches [MIN_COMMENT_LENGTH]. */
    fun isCommentLongEnough(comment: String): Boolean = comment.length >= MIN_COMMENT_LENGTH

    /** Whether [email] has a plausible local-part@domain.tld shape. */
    fun isValidEmail(email: String): Boolean = EMAIL_REGEX.matches(email.trim())
}

data class ReportReviewState(
    val loaded: Boolean = false,
    val exists: Boolean = false,
    val payload: String = "",
    val comment: String = "",
    val email: String = "",
    val canSubmitOnline: Boolean = false,
    val submit: SubmitUiState = SubmitUiState.IDLE,
) {
    /** True once [comment] reaches [ReportSubmissionRules.MIN_COMMENT_LENGTH]. */
    val isCommentLongEnough: Boolean get() = ReportSubmissionRules.isCommentLongEnough(comment)

    /** True once [email] is a plausible reply-to address. */
    val isEmailValid: Boolean get() = ReportSubmissionRules.isValidEmail(email)

    /** Submit gate: the original SUBMITTING check, plus the new length/email requirements. */
    val canSubmit: Boolean get() = submit != SubmitUiState.SUBMITTING && isCommentLongEnough && isEmailValid
}

@HiltViewModel
class ReportReviewViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val store: ReportStore,
    private val submitter: ReportSubmitter,
) : ViewModel() {

    private val reportId: String = checkNotNull(savedStateHandle[Routes.REPORT_REVIEW_ARG_ID])
    private val comment = MutableStateFlow(store.find(reportId)?.userComment.orEmpty())
    private val email = MutableStateFlow(store.find(reportId)?.userEmail.orEmpty())
    private val submitState = MutableStateFlow(SubmitUiState.IDLE)

    val state: StateFlow<ReportReviewState> =
        combine(store.reports, comment, email, submitState) { reports, currentComment, currentEmail, submit ->
            val report = reports.firstOrNull { it.id == reportId }
            ReportReviewState(
                loaded = true,
                exists = report != null,
                // The comment/email are folded in so the preview is byte-for-byte what a submit would send.
                payload = report?.copy(userComment = currentComment, userEmail = currentEmail)
                    ?.toSubmissionPayload().orEmpty(),
                comment = currentComment,
                email = currentEmail,
                canSubmitOnline = submitter.isEnabled,
                submit = submit,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_MS), ReportReviewState())

    fun updateComment(value: String) {
        comment.value = value
    }

    fun updateEmail(value: String) {
        email.value = value
    }

    fun discard() {
        viewModelScope.launch { store.delete(reportId) }
    }

    /**
     * The only path that can send a report off-device, and only from an explicit Submit tap. Persists
     * the reviewed comment first so the upload matches exactly what was shown, then enqueues the
     * worker (unless no endpoint is configured, in which case it steers the user to Copy/Save).
     *
     * Guarded by [ReportSubmissionRules] directly (rather than trusting the caller) so this can't
     * succeed with an under-length comment or an invalid email even if invoked outside the
     * Compose button's `enabled` gate — e.g. from an accessibility service activating a control it
     * still considers actionable.
     */
    fun submit() {
        // Re-entry guard + validation run SYNCHRONOUSLY, and SUBMITTING is flipped BEFORE the async
        // save/enqueue, so a double-tap can't launch two uploads before the first flips the flag (#304).
        if (submitState.value == SubmitUiState.SUBMITTING) return
        if (!ReportSubmissionRules.isCommentLongEnough(comment.value)) return
        if (!ReportSubmissionRules.isValidEmail(email.value)) return
        val report = store.find(reportId) ?: return
        submitState.value = SubmitUiState.SUBMITTING
        viewModelScope.launch {
            store.save(report.copy(userComment = comment.value, userEmail = email.value))
            if (!submitter.isEnabled) {
                submitState.value = SubmitUiState.UNAVAILABLE
                return@launch
            }
            submitter.submit(reportId)
            submitter.status(reportId).collect { submitState.value = it.toUi() }
        }
    }

    /** The exact text shown for review — used for Copy and Save-to-file. */
    fun payload(): String = store.find(reportId)?.copy(userComment = comment.value, userEmail = email.value)
        ?.toSubmissionPayload().orEmpty()

    private fun SubmitStatus.toUi(): SubmitUiState = when (this) {
        SubmitStatus.IDLE, SubmitStatus.SUBMITTING -> SubmitUiState.SUBMITTING
        SubmitStatus.SUCCEEDED -> SubmitUiState.SUCCEEDED
        SubmitStatus.FAILED -> SubmitUiState.FAILED
    }

    private companion object {
        const val SUBSCRIBE_MS = 5_000L
    }
}
