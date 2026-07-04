// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.reporting

import android.content.ClipData
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.libremail.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportReviewScreen(onDone: () -> Unit, viewModel: ReportReviewViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    // Latches once the post-submit confirmation dialog is acknowledged, so it can't reappear
    // during this screen's exit transition (state.submit stays SUCCEEDED after that point).
    var reportSubmittedAcknowledged by remember { mutableStateOf(false) }

    val savedMessage = stringResource(R.string.report_saved)
    val copiedMessage = stringResource(R.string.report_copied)

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            val text = viewModel.payload()
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use {
                            it.write(text.toByteArray())
                        }
                    }
                }
                snackbarHostState.showSnackbar(savedMessage)
            }
        }
    }

    // Leave once the report is gone — but a successful submit deletes the row from
    // ReportUploadWorker as soon as the upload finishes, which can race ahead of `state.submit`
    // itself reporting SUCCEEDED. So while a submit is in flight or has just succeeded, this
    // effect defers to ReportSubmittedDialog below: its acknowledgement calls onDone() instead,
    // guaranteeing the confirmation is seen. Plain discard (or a submit that never enqueued
    // anything, e.g. FAILED/UNAVAILABLE) is unaffected and still auto-navigates immediately.
    LaunchedEffect(state.loaded, state.exists, state.submit) {
        val awaitingSubmitOutcome =
            state.submit == SubmitUiState.SUBMITTING || state.submit == SubmitUiState.SUCCEEDED
        if (state.loaded && !state.exists && !awaitingSubmitOutcome) onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.report_review_title)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            PiiDisclaimer()
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.report_auto_delete_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = state.comment,
                onValueChange = viewModel::updateComment,
                label = { Text(stringResource(R.string.report_comment_label)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                isError = !state.isCommentLongEnough,
                supportingText = {
                    Text(
                        stringResource(
                            R.string.report_comment_counter,
                            state.comment.length,
                            ReportSubmissionRules.MIN_COMMENT_LENGTH,
                        ),
                    )
                },
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = state.email,
                onValueChange = viewModel::updateEmail,
                label = { Text(stringResource(R.string.report_email_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = !state.isEmailValid,
                supportingText = {
                    if (!state.isEmailValid) {
                        Text(stringResource(R.string.report_email_invalid))
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.report_email_consent),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.report_payload_label),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            PayloadBox(payload = state.payload)
            SubmitStatusText(state.submit)
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = viewModel::submit,
                enabled = state.submit != SubmitUiState.SUBMITTING &&
                    state.isCommentLongEnough &&
                    state.isEmailValid,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.report_submit))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = {
                        scope.launch {
                            copyReportPayloadToClipboard(clipboard, viewModel.payload())
                            snackbarHostState.showSnackbar(copiedMessage)
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.report_copy))
                }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = { saveLauncher.launch("libremail-report.json") },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.report_save))
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = viewModel::discard,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.report_discard),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    // Gates leaving the screen on the success path (see the LaunchedEffect above) so the message
    // is guaranteed to be seen, not just present for an instant before an auto-navigate.
    if (state.submit == SubmitUiState.SUCCEEDED && !reportSubmittedAcknowledged) {
        ReportSubmittedDialog(
            onAcknowledge = {
                reportSubmittedAcknowledged = true
                onDone()
            },
        )
    }
}

/** Label attached to the clip so OS-level clipboard UI (e.g. clipboard history) can identify it. */
private const val REPORT_CLIP_LABEL = "LibreMail report"

/**
 * Copies [payload] to the system clipboard as plain text via the suspend [Clipboard] API (#237 —
 * `LocalClipboardManager`/`ClipboardManager` are deprecated in favor of `LocalClipboard`).
 *
 * Kept as a plain suspend function rather than inlined in the composable so the "Copy report"
 * action's clipboard interaction is unit-testable directly against a fake/mock [Clipboard],
 * without a Compose UI test or emulator.
 */
internal suspend fun copyReportPayloadToClipboard(clipboard: Clipboard, payload: String) {
    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(REPORT_CLIP_LABEL, payload)))
}

@Composable
private fun PiiDisclaimer() {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp)) {
            Icon(Icons.Filled.Warning, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    stringResource(R.string.report_pii_disclaimer_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.report_pii_disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun PayloadBox(payload: String) {
    SelectionContainer {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = payload,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

@Composable
private fun SubmitStatusText(state: SubmitUiState) {
    // SUCCEEDED is surfaced via ReportSubmittedDialog instead: a modal is what guarantees the
    // message survives the screen's auto-navigate-on-delete race (see ReportReviewScreen above).
    val text = when (state) {
        SubmitUiState.SUBMITTING -> stringResource(R.string.report_submitting)
        SubmitUiState.FAILED -> stringResource(R.string.report_submit_failed)
        SubmitUiState.UNAVAILABLE -> stringResource(R.string.report_submit_unavailable)
        SubmitUiState.IDLE, SubmitUiState.SUCCEEDED -> return
    }
    val color = if (state == SubmitUiState.FAILED || state == SubmitUiState.UNAVAILABLE) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Spacer(Modifier.height(8.dp))
    Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
}

/**
 * Confirmation shown after a successful submission (#161). It — not the deleted-row auto-navigate
 * — is what leaves the screen for that path, so the fuller thank-you message is guaranteed to be
 * seen even though the report row (and therefore `state.exists`) can flip to gone moments after
 * `SubmitUiState.SUCCEEDED`, once `ReportUploadWorker` finishes.
 */
@Composable
private fun ReportSubmittedDialog(onAcknowledge: () -> Unit) {
    AlertDialog(
        onDismissRequest = onAcknowledge,
        text = { Text(stringResource(R.string.report_submitted)) },
        confirmButton = {
            TextButton(onClick = onAcknowledge) {
                Text(stringResource(R.string.report_submitted_dismiss))
            }
        },
    )
}
