// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.settings

import android.Manifest
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.libremail.R
import org.libremail.contacts.ContactPermissionDecision
import org.libremail.contacts.ContactPermissionState
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
    val appLockMessage by viewModel.appLockMessage.collectAsStateWithLifecycle()
    val batteryUnrestricted by viewModel.batteryUnrestricted.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Contacts-autocomplete entry (#129): its on / off / blocked-in-settings state is derived from the
    // live grant, the Activity's rationale signal, and whether the dialog was ever shown — recomputed
    // on resume (e.g. back from system settings) and when the "requested" flag flips.
    val contactsRequested by viewModel.contactsPermissionRequested.collectAsStateWithLifecycle()
    var contactsState by remember { mutableStateOf(ContactPermissionState.DENIED) }
    var showContactsRationale by remember { mutableStateOf(false) }
    var showContactsBlocked by remember { mutableStateOf(false) }
    fun resolveContactsState() = ContactPermissionDecision.resolve(
        granted = viewModel.hasContactsPermission(),
        showRationale = activity != null &&
            ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.READ_CONTACTS),
        alreadyRequested = contactsRequested,
    )
    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { contactsState = resolveContactsState() }
    LaunchedEffect(contactsRequested) { contactsState = resolveContactsState() }

    // Surface a rejected app-lock toggle via the canonical snackbar pattern (matches MailboxScreen).
    // The ViewModel holds the @StringRes id; resolve it here via LocalResources (so it re-resolves on
    // configuration changes) at the display boundary, then clear it.
    LaunchedEffect(appLockMessage) {
        appLockMessage?.let { messageId ->
            snackbarHostState.showSnackbar(resources.getString(messageId))
            viewModel.clearAppLockMessage()
        }
    }

    // Re-read the battery + contacts state on resume so both reflect changes made in system settings.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshBatteryStatus()
        contactsState = resolveContactsState()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.title_settings)) }) },
        bottomBar = { LibreMailBottomBar(current = TopDest.SETTINGS, onSelect = onSelectTab) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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

            SectionHeader(stringResource(R.string.settings_contacts))
            ContactAutocompleteRow(
                state = contactsState,
                onClick = {
                    when (contactsState) {
                        // Already on: send to system settings, the only place to turn it back off.
                        ContactPermissionState.GRANTED ->
                            runCatching { context.startActivity(viewModel.contactsSettingsIntent()) }
                        // Re-requestable in-app: explain first (#128), then launch the system dialog.
                        ContactPermissionState.DENIED -> showContactsRationale = true
                        // Permanently denied: an in-app request is a no-op, so deep-link to settings.
                        ContactPermissionState.BLOCKED -> showContactsBlocked = true
                    }
                },
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

            // Global device-only retention default (issue #13); accounts may override it.
            RetentionSection(
                count = settings.retentionCount,
                months = settings.retentionMonths,
                includeUseDefault = false,
                onCountChange = { viewModel.setRetentionCount(it ?: 0) },
                onMonthsChange = { viewModel.setRetentionMonths(it ?: 0) },
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
                    ClickRow(
                        title = stringResource(R.string.settings_adv_battery),
                        subtitle = stringResource(
                            if (batteryUnrestricted) {
                                R.string.settings_adv_battery_unrestricted
                            } else {
                                R.string.settings_adv_battery_optimized
                            },
                        ),
                        onClick = { runCatching { context.startActivity(viewModel.batterySettingsIntent()) } },
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
                    SwitchRow(
                        title = stringResource(R.string.settings_adv_app_lock),
                        checked = settings.appLock,
                        onCheckedChange = viewModel::setAppLock,
                        subtitle = stringResource(R.string.settings_adv_app_lock_summary),
                    )
                }
            }
        }
    }

    if (showContactsRationale) {
        ContactsPermissionDialog(
            title = stringResource(R.string.settings_contacts_dialog_title),
            body = stringResource(R.string.settings_contacts_rationale),
            confirm = stringResource(R.string.settings_contacts_allow),
            onConfirm = {
                showContactsRationale = false
                // Mark the dialog as shown BEFORE launching, so a permanent denial reads as "blocked".
                viewModel.markContactsPermissionRequested()
                contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
            },
            onDismiss = { showContactsRationale = false },
        )
    }
    if (showContactsBlocked) {
        ContactsPermissionDialog(
            title = stringResource(R.string.settings_contacts_dialog_title),
            body = stringResource(R.string.settings_contacts_blocked_body),
            confirm = stringResource(R.string.settings_contacts_open_settings),
            onConfirm = {
                showContactsBlocked = false
                runCatching { context.startActivity(viewModel.contactsSettingsIntent()) }
            },
            onDismiss = { showContactsBlocked = false },
        )
    }
}

/**
 * The contacts-autocomplete row (#129). Its subtitle reflects the current [state]: on, off (tap to
 * turn on), or blocked in system settings. Extracted so each state renders deterministically in tests.
 */
@Composable
internal fun ContactAutocompleteRow(state: ContactPermissionState, onClick: () -> Unit) {
    val subtitleRes = when (state) {
        ContactPermissionState.GRANTED -> R.string.settings_contacts_autocomplete_on
        ContactPermissionState.DENIED -> R.string.settings_contacts_autocomplete_off
        ContactPermissionState.BLOCKED -> R.string.settings_contacts_autocomplete_blocked
    }
    ClickRow(
        title = stringResource(R.string.settings_contacts_autocomplete),
        subtitle = stringResource(subtitleRes),
        onClick = onClick,
    )
}

/** Shared confirm/cancel dialog for the contacts rationale (before a request) and the blocked case. */
@Composable
private fun ContactsPermissionDialog(
    title: String,
    body: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
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
