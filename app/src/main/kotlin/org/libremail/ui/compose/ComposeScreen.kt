// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.compose

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collect
import org.libremail.R
import org.libremail.domain.model.Account
import org.libremail.domain.model.OutgoingAttachment

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(
    onBack: () -> Unit,
    viewModel: ComposeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.onContactsPermission(granted) }

    val attachmentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        viewModel.addAttachments(
            uris.map { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                OutgoingAttachment(uri.toString(), queryFileName(context, uri))
            },
        )
    }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.onContactsPermission(true) else permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
    }
    LaunchedEffect(Unit) { viewModel.finished.collect { onBack() } }
    BackHandler { viewModel.onExit() }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_compose)) },
                navigationIcon = {
                    IconButton(onClick = viewModel::onExit) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = viewModel::send,
                        enabled = state.to.isNotBlank() && !state.sending,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.action_send))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                FromRow(accounts = accounts, selectedId = state.fromAccountId, onSelect = viewModel::selectFrom)
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = state.to,
                    onValueChange = viewModel::onToChange,
                    label = { Text(stringResource(R.string.compose_to)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.suggestions.isNotEmpty()) {
                    SuggestionList(state.suggestions, viewModel::pickSuggestion)
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.cc,
                    onValueChange = viewModel::onCcChange,
                    label = { Text(stringResource(R.string.compose_cc)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.subject,
                    onValueChange = viewModel::onSubjectChange,
                    label = { Text(stringResource(R.string.compose_subject)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                AttachmentsSection(
                    attachments = state.attachments,
                    onAttach = { attachmentPicker.launch(arrayOf("*/*")) },
                    onRemove = viewModel::removeAttachment,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.body,
                    onValueChange = viewModel::onBodyChange,
                    label = { Text(stringResource(R.string.compose_body)) },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }

            if (state.sending) {
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

@Composable
private fun FromRow(accounts: List<Account>, selectedId: String?, onSelect: (String) -> Unit) {
    val from = accounts.firstOrNull { it.id == selectedId } ?: accounts.firstOrNull()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.compose_from) + ": ",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (from == null) {
            Text(stringResource(R.string.compose_no_account), style = MaterialTheme.typography.bodyMedium)
        } else if (accounts.size <= 1) {
            Text(from.email, style = MaterialTheme.typography.bodyMedium)
        } else {
            var open by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { open = true }) {
                    Text(from.email)
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    accounts.forEach { account ->
                        DropdownMenuItem(
                            text = { Text(account.email) },
                            onClick = { onSelect(account.id); open = false },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachmentsSection(
    attachments: List<OutgoingAttachment>,
    onAttach: () -> Unit,
    onRemove: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        TextButton(onClick = onAttach) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.compose_attach))
        }
        attachments.forEach { attachment ->
            InputChip(
                selected = false,
                onClick = { onRemove(attachment.uri) },
                label = { Text(attachment.name, maxLines = 1) },
                trailingIcon = {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.compose_attachment_remove))
                },
            )
        }
    }
}

private fun queryFileName(context: Context, uri: Uri): String {
    val name = context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    return name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
}

@Composable
private fun SuggestionList(
    suggestions: List<org.libremail.contacts.ContactSuggestion>,
    onPick: (org.libremail.contacts.ContactSuggestion) -> Unit,
) {
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column {
            suggestions.forEach { suggestion ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(suggestion) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(suggestion.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        suggestion.email,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}
