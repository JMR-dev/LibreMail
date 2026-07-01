// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.accountsetup

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.libremail.R
import org.libremail.domain.model.MailSecurity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualSetupScreen(
    onBack: () -> Unit,
    onAccountAdded: (String) -> Unit,
    viewModel: ManualSetupViewModel = hiltViewModel(),
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(form.status, form.addedAccountId) {
        if (form.status == SetupStatus.DONE) {
            form.addedAccountId?.let(onAccountAdded)
        }
    }
    LaunchedEffect(form.error) {
        form.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeError()
        }
    }

    val busy = form.status == SetupStatus.CONNECTING

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.manual_setup_title)) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            OutlinedTextField(
                value = form.email,
                onValueChange = viewModel::onEmail,
                label = { Text(stringResource(R.string.manual_email)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = form.password,
                onValueChange = viewModel::onPassword,
                label = { Text(stringResource(R.string.manual_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(20.dp))
            SectionLabel(stringResource(R.string.manual_incoming))
            OutlinedTextField(
                value = form.imapHost,
                onValueChange = viewModel::onImapHost,
                label = { Text(stringResource(R.string.manual_imap_server)) },
                placeholder = { Text("imap.example.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            SectionLabel(stringResource(R.string.manual_outgoing))
            OutlinedTextField(
                value = form.smtpHost,
                onValueChange = viewModel::onSmtpHost,
                label = { Text(stringResource(R.string.manual_smtp_server)) },
                placeholder = { Text("smtp.example.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))
            AdvancedToggle(expanded = form.advancedExpanded, onToggle = viewModel::toggleAdvanced)
            AnimatedVisibility(visible = form.advancedExpanded) {
                Column {
                    OutlinedTextField(
                        value = form.imapPort,
                        onValueChange = viewModel::onImapPort,
                        label = { Text(stringResource(R.string.manual_imap_port)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    SecuritySelector(
                        stringResource(R.string.manual_imap_security),
                        form.imapSecurity,
                        viewModel::onImapSecurity,
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = form.smtpPort,
                        onValueChange = viewModel::onSmtpPort,
                        label = { Text(stringResource(R.string.manual_smtp_port)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    SecuritySelector(
                        stringResource(R.string.manual_smtp_security),
                        form.smtpSecurity,
                        viewModel::onSmtpSecurity,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = viewModel::testAndSave,
                enabled = form.isValid && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.manual_test_and_add))
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SecuritySelector(label: String, selected: MailSecurity, onSelect: (MailSecurity) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // NONE (no transport security) is deliberately not offered in the UI: selecting it would
            // send the account password/token in cleartext. It stays in the enum only for local
            // test servers, which are configured in tests rather than through this screen.
            MailSecurity.entries.filter { it != MailSecurity.NONE }.forEach { security ->
                FilterChip(
                    selected = selected == security,
                    onClick = { onSelect(security) },
                    label = { Text(security.label()) },
                )
            }
        }
    }
}

@Composable
private fun AdvancedToggle(expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.settings_advanced),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Filled.ArrowDropDown,
            contentDescription = null,
            modifier = Modifier.rotate(if (expanded) 180f else 0f),
        )
    }
}

private fun MailSecurity.label(): String = when (this) {
    MailSecurity.SSL_TLS -> "SSL/TLS"
    MailSecurity.STARTTLS -> "STARTTLS"
    MailSecurity.NONE -> "None"
}
