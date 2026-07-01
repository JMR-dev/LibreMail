// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.libremail.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSettingsScreen(
    onBack: () -> Unit,
    viewModel: AccountSettingsViewModel = hiltViewModel(),
) {
    val account by viewModel.account.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val signature by viewModel.signature.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val fallbackTitle = stringResource(R.string.settings_account_title)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(account?.email ?: fallbackTitle) },
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
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader(stringResource(R.string.settings_signature))
            SwitchRow(
                title = stringResource(R.string.settings_signature_enable),
                checked = settings.signatureEnabled,
                onCheckedChange = viewModel::setSignatureEnabled,
            )
            OutlinedTextField(
                value = signature ?: "",
                onValueChange = viewModel::onSignatureChange,
                enabled = settings.signatureEnabled,
                label = { Text(stringResource(R.string.settings_signature_hint)) },
                minLines = 3,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HorizontalDivider()

            SectionHeader(stringResource(R.string.settings_account_notifications))
            SwitchRow(
                title = stringResource(R.string.settings_account_new_mail),
                checked = settings.notificationsEnabled,
                onCheckedChange = viewModel::setNotificationsEnabled,
                subtitle = stringResource(R.string.settings_account_new_mail_summary),
            )
            ClickRow(
                title = stringResource(R.string.settings_account_system_notif),
                subtitle = stringResource(R.string.settings_account_system_notif_summary),
                onClick = { openChannelSettings(context, viewModel.notificationChannelId) },
            )
            HorizontalDivider()

            ClickRow(
                title = stringResource(R.string.account_remove),
                titleColor = MaterialTheme.colorScheme.error,
                onClick = { viewModel.removeAccount(onBack) },
            )
        }
    }
}

/**
 * Opens Android's system notification settings for this account's channel (where the user controls
 * sound, vibration, and importance). Falls back to the app's notification settings if the channel
 * screen can't be shown.
 */
private fun openChannelSettings(context: Context, channelId: String) {
    val channelIntent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
    }
    runCatching { context.startActivity(channelIntent) }.onFailure {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        }
    }
}
