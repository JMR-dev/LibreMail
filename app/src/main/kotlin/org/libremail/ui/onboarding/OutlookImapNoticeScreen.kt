// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.libremail.R
import org.libremail.reporting.AppLog
import org.libremail.ui.accountsetup.AccountSetupViewModel
import org.libremail.ui.accountsetup.SetupStatus

/**
 * Pre-auth interstitial shown when the user picks Outlook during onboarding (#411), before the
 * Microsoft OAuth browser opens. New personal outlook.com accounts ship with IMAP **OFF** by
 * default, so OAuth can succeed while the later IMAP `AUTHENTICATE` step fails — a confusing
 * dead-end (the *reactive* complement is #390). This screen asks the user to confirm IMAP is on
 * first, links Microsoft's help article and the Outlook IMAP settings page, and only then continues
 * the **existing** Outlook OAuth flow from its bottom "Sign in" button.
 *
 * The sign-in wiring is identical to the picker's Outlook row: build the AppAuth intent via
 * [AccountSetupViewModel.outlookAuthIntent], launch it, and hand the redirect back to
 * [AccountSetupViewModel.onOutlookResult]; a completed add is reported through [onAccountAdded]. No
 * PII is logged — the account address is embedded in the id and never touched here (see
 * [AccountSetupViewModel.onOutlookResult] for the add breadcrumb).
 *
 * @param onBack returns to the vendor picker (e.g. to choose a different provider).
 * @param onAccountAdded invoked with the new account id once the Outlook flow completes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutlookImapNoticeScreen(
    onBack: () -> Unit,
    onAccountAdded: (String) -> Unit,
    viewModel: AccountSetupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    // Resolved up front so the non-composable failure handler can use it.
    val openFailedMessage = stringResource(R.string.app_password_open_failed)

    val outlookLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result -> viewModel.onOutlookResult(result.data) }

    // One-shot breadcrumb so a debug report shows the user reached the pre-auth IMAP notice (#411).
    LaunchedEffect(Unit) { AppLog.i(TAG, "Outlook IMAP notice shown") }

    LaunchedEffect(state.status, state.addedAccountId) {
        if (state.status == SetupStatus.DONE) {
            state.addedAccountId?.let(onAccountAdded)
        }
    }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeError()
        }
    }

    // openUri throws when no browser/handler is installed; surface that as a snackbar, not a crash.
    val openUrl: (String) -> Unit = { url ->
        runCatching { uriHandler.openUri(url) }
            .onFailure { scope.launch { snackbarHostState.showSnackbar(openFailedMessage) } }
    }

    val busy = state.status == SetupStatus.CONNECTING

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.outlook_imap_title)) },
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
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            ) {
                Icon(
                    Icons.Filled.Email,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    text = stringResource(R.string.outlook_imap_question),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.outlook_imap_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
                OutlinedButton(
                    onClick = {
                        AppLog.i(TAG, "Outlook IMAP help article opened")
                        openUrl(IMAP_HELP_URL)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.outlook_imap_help))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        AppLog.i(TAG, "Outlook IMAP settings page opened")
                        openUrl(IMAP_SETTINGS_URL)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.outlook_imap_settings))
                }
                Spacer(Modifier.height(32.dp))
                // Bottom of the visual hierarchy (#411): continues the existing Outlook OAuth flow,
                // unchanged. Append any new controls AFTER this so onboarding E2E clicks stay stable.
                Button(
                    onClick = {
                        AppLog.i(TAG, "Outlook sign-in continued from IMAP notice")
                        viewModel.outlookAuthIntent().fold(
                            onSuccess = { intent ->
                                runCatching { outlookLauncher.launch(intent) }
                                    .onFailure { viewModel.onOutlookLaunchFailed(it) }
                            },
                            onFailure = { viewModel.onOutlookLaunchFailed(it) },
                        )
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.outlook_imap_sign_in))
                }
            }
            if (busy) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

private const val TAG = "OutlookImapNotice"

// Microsoft's canonical "POP, IMAP, and SMTP settings for Outlook.com" support article — the
// authoritative walkthrough for switching IMAP on (verified 2026-07, issue #411).
private const val IMAP_HELP_URL =
    "https://support.microsoft.com/en-us/office/pop-imap-and-smtp-settings-for-outlook-com-" +
        "d088b986-291d-42b8-9564-9c414e2aa040"

// Deep link to the Outlook.com POP/IMAP settings page ("Let devices and apps use IMAP"). If
// Microsoft changes the options path this still lands the user in Outlook.com mail settings; the
// help article above is the durable fallback.
private const val IMAP_SETTINGS_URL = "https://outlook.live.com/mail/0/options/mail/accounts/popImap"
