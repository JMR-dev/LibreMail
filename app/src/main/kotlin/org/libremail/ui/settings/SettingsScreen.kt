// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.libremail.R
import org.libremail.data.settings.FetchPolicy
import org.libremail.ui.LibreMailBottomBar
import org.libremail.ui.TopDest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onAddAccount: () -> Unit,
    onOpenAccount: (String) -> Unit,
    onSelectTab: (TopDest) -> Unit,
    onReportProblem: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val advancedExpanded by viewModel.advancedExpanded.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.title_settings)) }) },
        bottomBar = { LibreMailBottomBar(current = TopDest.SETTINGS, onSelect = onSelectTab) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader(stringResource(R.string.settings_accounts))
            if (accounts.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_no_accounts),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            } else {
                accounts.forEach { account ->
                    ClickRow(title = account.email, onClick = { onOpenAccount(account.id) })
                }
            }
            ClickRow(title = stringResource(R.string.settings_add_account), onClick = onAddAccount)
            HorizontalDivider()

            SectionHeader(stringResource(R.string.settings_notifications))
            SwitchRow(
                title = stringResource(R.string.settings_new_mail),
                checked = settings.newMailNotifications,
                onCheckedChange = viewModel::setNewMailNotifications,
                subtitle = stringResource(R.string.settings_new_mail_summary),
            )
            HorizontalDivider()

            SectionHeader(stringResource(R.string.settings_appearance))
            SwitchRow(
                title = stringResource(R.string.settings_dynamic_color),
                checked = settings.dynamicColor,
                onCheckedChange = viewModel::setDynamicColor,
                subtitle = stringResource(R.string.settings_dynamic_color_summary),
            )
            HorizontalDivider()

            SectionHeader(stringResource(R.string.settings_downloading))
            RadioRow(
                title = stringResource(R.string.fetch_always),
                subtitle = stringResource(R.string.fetch_always_summary),
                selected = settings.fetchPolicy == FetchPolicy.ALWAYS,
                onClick = { viewModel.setFetchPolicy(FetchPolicy.ALWAYS) },
            )
            RadioRow(
                title = stringResource(R.string.fetch_wifi),
                subtitle = stringResource(R.string.fetch_wifi_summary),
                selected = settings.fetchPolicy == FetchPolicy.WIFI_ONLY,
                onClick = { viewModel.setFetchPolicy(FetchPolicy.WIFI_ONLY) },
            )
            RadioRow(
                title = stringResource(R.string.fetch_on_demand),
                subtitle = stringResource(R.string.fetch_on_demand_summary),
                selected = settings.fetchPolicy == FetchPolicy.ON_DEMAND,
                onClick = { viewModel.setFetchPolicy(FetchPolicy.ON_DEMAND) },
            )
            HorizontalDivider()

            SectionHeader(stringResource(R.string.settings_diagnostics))
            ClickRow(
                title = stringResource(R.string.settings_report_problem),
                subtitle = stringResource(R.string.settings_report_problem_summary),
                onClick = onReportProblem,
            )
            HorizontalDivider()

            SectionHeader(stringResource(R.string.settings_backup))
            SwitchRow(
                title = stringResource(R.string.settings_backup_include),
                checked = settings.includeInBackup,
                onCheckedChange = viewModel::setIncludeInBackup,
                subtitle = stringResource(R.string.settings_backup_include_summary),
            )
            HorizontalDivider()

            AdvancedHeader(expanded = advancedExpanded, onToggle = viewModel::toggleAdvanced)
            AnimatedVisibility(visible = advancedExpanded) {
                Column {
                    SwitchRow(
                        title = stringResource(R.string.settings_adv_idle),
                        checked = settings.pushIdle,
                        onCheckedChange = viewModel::setPushIdle,
                    )
                    SwitchRow(
                        title = stringResource(R.string.settings_adv_starttls),
                        checked = settings.allowStartTls,
                        onCheckedChange = viewModel::setAllowStartTls,
                        subtitle = stringResource(R.string.settings_adv_starttls_summary),
                    )
                    SwitchRow(
                        title = stringResource(R.string.settings_adv_remote_images),
                        checked = settings.loadRemoteImages,
                        onCheckedChange = viewModel::setLoadRemoteImages,
                    )
                    SwitchRow(
                        title = stringResource(R.string.settings_adv_encrypt_cache),
                        checked = settings.encryptCache,
                        onCheckedChange = viewModel::setEncryptCache,
                        subtitle = stringResource(R.string.settings_adv_encrypt_cache_summary),
                    )
                }
            }
        }
    }
}

@Composable
private fun RadioRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AdvancedHeader(expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_advanced), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.settings_advanced_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.Filled.ArrowDropDown,
            contentDescription = null,
            modifier = Modifier.rotate(if (expanded) 180f else 0f),
        )
    }
}
