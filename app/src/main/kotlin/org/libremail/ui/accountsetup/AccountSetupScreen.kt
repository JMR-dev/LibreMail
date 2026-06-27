// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.accountsetup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.libremail.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSetupScreen(
    onBack: () -> Unit,
    onManualSetup: () -> Unit,
    onAccountAdded: () -> Unit,
    viewModel: AccountSetupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val needsClientId = stringResource(R.string.account_gmail_needs_client_id)

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result -> viewModel.onGmailResult(result.data) }

    val outlookLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result -> viewModel.onOutlookResult(result.data) }

    LaunchedEffect(state.status) {
        if (state.status == SetupStatus.DONE) onAccountAdded()
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Filled.Email,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.account_setup_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = {
                        if (viewModel.isGmailConfigured) {
                            launcher.launch(viewModel.gmailAuthIntent())
                        } else {
                            scope.launch { snackbarHostState.showSnackbar(needsClientId) }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.account_setup_gmail))
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { outlookLauncher.launch(viewModel.outlookAuthIntent()) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.account_setup_outlook))
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onManualSetup,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.account_setup_other))
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
