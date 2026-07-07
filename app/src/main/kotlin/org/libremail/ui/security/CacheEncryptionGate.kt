// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.security

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.libremail.R
import org.libremail.ui.reporting.copyReportPayloadToClipboard

/**
 * Fail-closed gate for the opt-in encrypted cache (issue #359). Wraps the whole app: while
 * [CacheEncryptionGateViewModel] resolves whether the encrypted cache can be opened it shows a blank
 * cover, and [content] — the real app — composes only once the gate reports
 * [CacheEncryptionGateState.Ready]. If SQLCipher's native library will not load, the gate shows
 * [CacheEncryptionErrorScreen] instead of ever opening the cache unencrypted or reaching the mailbox.
 *
 * Hosted INSIDE the app-lock gate (`AppLockGateHost`), so when app-lock is on the passphrase is already
 * unlocked before the probe runs.
 */
@Composable
fun CacheEncryptionGate(viewModel: CacheEncryptionGateViewModel = hiltViewModel(), content: @Composable () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (state) {
        CacheEncryptionGateState.Checking -> GateCover()
        CacheEncryptionGateState.Ready -> content()
        CacheEncryptionGateState.Unavailable -> CacheEncryptionErrorFlow(viewModel)
    }
}

/** Opaque cover shown while the gate resolves, so no DB-backed screen is visible before the decision. */
@Composable
private fun GateCover() {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
}

/** Error screen ↔ ephemeral report review, kept off the app's NavHost (the app never composed here). */
@Composable
private fun CacheEncryptionErrorFlow(viewModel: CacheEncryptionGateViewModel) {
    var reviewing by rememberSaveable { mutableStateOf(false) }
    if (reviewing) {
        val payload by viewModel.reportPayload.collectAsStateWithLifecycle()
        EphemeralReportReviewScreen(
            payload = payload,
            onBack = {
                viewModel.dismissReport()
                reviewing = false
            },
        )
    } else {
        CacheEncryptionErrorScreen(
            onReportProblem = {
                viewModel.prepareReport()
                reviewing = true
            },
        )
    }
}

/**
 * Full-screen fail-closed notice shown when the encrypted cache cannot be opened. Presentational: the
 * verbatim error message, a reassurance that nothing was lost, and a "Report a problem" action wired by
 * the caller. Deliberately renders no mailbox content.
 */
@Composable
fun CacheEncryptionErrorScreen(onReportProblem: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.cache_encryption_error_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            // The exact maintainer-specified message; do not reword.
            Text(
                text = stringResource(R.string.cache_encryption_error_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.cache_encryption_error_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onReportProblem) {
                Text(stringResource(R.string.cache_encryption_report_action))
            }
        }
    }
}

/**
 * Ephemeral review of the PII-free diagnostic report generated from the error gate. The report is held
 * only in memory (never written to disk); the copy states that plainly. The user can Copy it to the
 * clipboard or Save it to a file they choose — the only ways it leaves this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EphemeralReportReviewScreen(payload: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val copiedMessage = stringResource(R.string.report_copied)
    val savedMessage = stringResource(R.string.report_saved)

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val text = payload
        if (uri != null && text != null) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                    }
                }
                snackbarHostState.showSnackbar(savedMessage)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.report_review_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                stringResource(R.string.cache_encryption_report_ephemeral_notice),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            if (payload == null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.cache_encryption_report_generating),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
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
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                copyReportPayloadToClipboard(clipboard, payload)
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
            }
        }
    }
}
