// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.accountsetup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.libremail.R
import org.libremail.domain.model.MailProvider

/**
 * The single account-vendor picker used both by first-run onboarding and the "Add account" entry
 * from Settings/mailbox. It routes each choice to the correct setup path:
 *  - Outlook/Hotmail → the existing Microsoft OAuth flow, completed inline via [AccountSetupViewModel].
 *  - Gmail / Yahoo / iCloud → the guided app-password screen with the matching [MailProvider] preset.
 *  - Other (IMAP/SMTP) → the manual setup screen.
 *
 * @param onAccountAdded invoked with the new account id when the *inline* Outlook flow completes.
 *   The app-password and manual paths report their own completion from their own screens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountPickerScreen(
    onBack: () -> Unit,
    onAccountAdded: (String) -> Unit,
    onPickProvider: (MailProvider) -> Unit,
    onManualSetup: () -> Unit,
    viewModel: AccountSetupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val outlookLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result -> viewModel.onOutlookResult(result.data) }

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

    val busy = state.status == SetupStatus.CONNECTING

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_account_setup)) },
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
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.account_setup_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
                ProviderRow(
                    // Recognizable brand logos would need bundled trademarked assets; until those
                    // exist we use a neutral mail glyph and rely on the visible label for recognition.
                    icon = Icons.Filled.Email,
                    label = stringResource(R.string.account_setup_outlook),
                    enabled = !busy,
                    onClick = {
                        viewModel.outlookAuthIntent().fold(
                            onSuccess = { intent ->
                                runCatching { outlookLauncher.launch(intent) }
                                    .onFailure { viewModel.onOutlookLaunchFailed(it) }
                            },
                            onFailure = { viewModel.onOutlookLaunchFailed(it) },
                        )
                    },
                )
                MailProvider.entries.forEach { provider ->
                    ProviderRow(
                        icon = Icons.Filled.Email,
                        label = provider.displayName,
                        enabled = !busy,
                        onClick = { onPickProvider(provider) },
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                ProviderRow(
                    icon = Icons.Filled.Lock,
                    label = stringResource(R.string.account_setup_other),
                    enabled = !busy,
                    onClick = onManualSetup,
                )
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
            // Outlook OAuth can succeed while the IMAP AUTHENTICATE step is rejected because IMAP is
            // off for the mailbox (#390); show the actionable "turn on IMAP" prompt instead of a
            // generic auth-failure snackbar.
            state.imapDisabledPrompt?.let { prompt ->
                ImapDisabledDialog(prompt = prompt, onDismiss = viewModel::dismissImapDisabledPrompt)
            }
        }
    }
}

@Composable
private fun ProviderRow(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClickLabel = label, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
