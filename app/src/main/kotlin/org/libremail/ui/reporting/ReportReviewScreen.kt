// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.reporting

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
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
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

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

    // Once the report has been submitted (deleted by the worker) or discarded, leave the screen.
    LaunchedEffect(state.loaded, state.exists) {
        if (state.loaded && !state.exists) onDone()
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
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = state.comment,
                onValueChange = viewModel::updateComment,
                label = { Text(stringResource(R.string.report_comment_label)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
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
                enabled = state.submit != SubmitUiState.SUBMITTING,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.report_submit))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(viewModel.payload()))
                        scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
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
    if (state == SubmitUiState.IDLE) return
    val text = when (state) {
        SubmitUiState.SUBMITTING -> stringResource(R.string.report_submitting)
        SubmitUiState.SUCCEEDED -> stringResource(R.string.report_submitted)
        SubmitUiState.FAILED -> stringResource(R.string.report_submit_failed)
        SubmitUiState.UNAVAILABLE -> stringResource(R.string.report_submit_unavailable)
        SubmitUiState.IDLE -> ""
    }
    val color = when (state) {
        SubmitUiState.SUCCEEDED -> MaterialTheme.colorScheme.primary
        SubmitUiState.FAILED, SubmitUiState.UNAVAILABLE -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Spacer(Modifier.height(8.dp))
    Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
}
