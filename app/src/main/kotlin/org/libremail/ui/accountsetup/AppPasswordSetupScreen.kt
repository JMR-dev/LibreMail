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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.libremail.R
import org.libremail.domain.model.MailProvider
import org.libremail.domain.model.MailSecurity
import org.libremail.domain.model.ServerConfig

/**
 * Guided app-password setup for the preset vendors (Gmail/Yahoo/iCloud). Explains what an app
 * password is, warns to keep it safe, links out to the provider's app-password page, and collects
 * only an email + app password (the servers come from the [MailProvider] preset). Verifies and
 * persists via the same repository path as manual setup, surfacing failures as an inline snackbar.
 *
 * @param onAccountAdded invoked with the new account id after a successful add.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPasswordSetupScreen(
    onBack: () -> Unit,
    onAccountAdded: (String) -> Unit,
    viewModel: AppPasswordViewModel = hiltViewModel(),
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val provider = viewModel.provider
    val snackbarHostState = remember { SnackbarHostState() }
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    // Resolved up front so the failure handler (a non-composable lambda) can use it.
    val openFailedMessage = stringResource(R.string.app_password_open_failed)
    // Shared by every outbound link on this screen: openUri throws if no browser/handler is
    // installed, so surface that as an inline snackbar instead of crashing.
    val openUrl: (String) -> Unit = { url ->
        runCatching { uriHandler.openUri(url) }
            .onFailure { scope.launch { snackbarHostState.showSnackbar(openFailedMessage) } }
    }

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
                title = {
                    Text(
                        provider?.let { stringResource(R.string.app_password_title, it.displayName) }
                            ?: stringResource(R.string.title_account_setup),
                    )
                },
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
        if (provider == null) {
            // Defensive: onboarding only ever routes valid provider keys here.
            Text(
                text = stringResource(R.string.app_password_unknown_provider),
                modifier = Modifier.padding(padding).padding(24.dp),
            )
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            InfoCard(
                icon = Icons.Filled.Info,
                text = stringResource(providerIntro(provider)),
            )
            Spacer(Modifier.height(8.dp))
            InfoCard(
                icon = Icons.Filled.Info,
                text = stringResource(R.string.app_password_what_is),
            )
            Spacer(Modifier.height(8.dp))
            InfoCard(
                icon = Icons.Filled.Warning,
                text = stringResource(R.string.app_password_warning),
            )

            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { openUrl(provider.appPasswordHelpUrl) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.app_password_open_page, provider.displayName))
            }
            // Only Gmail has a two-factor prerequisite (see MailProvider.twoFactorHelpUrl): its
            // app-passwords page rejects accounts without 2-Step Verification, so give those users
            // a way to set it up instead of a dead end.
            provider.twoFactorHelpUrl?.let { twoFactorHelpUrl ->
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { openUrl(twoFactorHelpUrl) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.app_password_2fa_help))
                }
            }

            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = form.email,
                onValueChange = viewModel::onEmail,
                label = { Text(stringResource(R.string.app_password_email)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = form.appPassword,
                onValueChange = viewModel::onAppPassword,
                label = { Text(stringResource(R.string.app_password_field)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))
            val account = remember(provider) { provider.createAccount("") }
            AdvancedServers(
                expanded = form.advancedExpanded,
                onToggle = viewModel::toggleAdvanced,
                imap = account.imap,
                smtp = account.smtp,
            )

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
                Text(stringResource(R.string.app_password_test_and_add))
            }
        }
    }
}

@Composable
private fun InfoCard(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp).padding(top = 2.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A collapsible, read-only view of the preset servers for users who want to confirm them. */
@Composable
private fun AdvancedServers(expanded: Boolean, onToggle: () -> Unit, imap: ServerConfig, smtp: ServerConfig) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.app_password_show_servers),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Filled.ArrowDropDown,
            contentDescription = null,
            modifier = Modifier.rotate(if (expanded) 180f else 0f),
        )
    }
    AnimatedVisibility(visible = expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.app_password_server_imap, imap.host, imap.port, imap.security.label()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.app_password_server_smtp, smtp.host, smtp.port, smtp.security.label()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun providerIntro(provider: MailProvider): Int = when (provider) {
    MailProvider.GMAIL -> R.string.app_password_intro_gmail
    MailProvider.YAHOO -> R.string.app_password_intro_yahoo
    MailProvider.ICLOUD -> R.string.app_password_intro_icloud
}

private fun MailSecurity.label(): String = when (this) {
    MailSecurity.SSL_TLS -> "SSL/TLS"
    MailSecurity.STARTTLS -> "STARTTLS"
    MailSecurity.NONE -> "None"
}
