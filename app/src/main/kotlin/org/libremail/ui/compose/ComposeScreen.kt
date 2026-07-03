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
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collect
import org.libremail.R
import org.libremail.domain.model.Account
import org.libremail.domain.model.OutgoingAttachment
import org.libremail.ui.compose.format.FontRegistry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(onBack: () -> Unit, viewModel: ComposeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

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

    // Inline-image picker (image/*). Mirrors the attachment picker's persistable grant so a draft can
    // reopen the image later; the ViewModel then hands the editor a token to drop at the caret.
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.onImagePicked(it.toString(), queryFileName(context, it))
        }
    }

    // Reflect the current READ_CONTACTS grant without ever prompting: the request now lives in the
    // onboarding contacts step (#127) and the Settings entry (#129), so compose only reads state.
    // Re-checked on resume so enabling autocomplete later (e.g. from Settings) takes effect the next
    // time compose is shown. Denial degrades gracefully — searchContacts() guards on this flag.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onContactsPermission(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    // Flush the pending debounced draft save when the app is backgrounded (#177), so the latest edits
    // aren't lost if the process is killed before the autosave debounce fires.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        viewModel.flushDraft()
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
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
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
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.suggestions.isNotEmpty()) {
                    SuggestionList(state.suggestions, viewModel::pickSuggestion)
                }

                CcBccFields(
                    cc = state.cc,
                    onCcChange = viewModel::onCcChange,
                    bcc = state.bcc,
                    onBccChange = viewModel::onBccChange,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.subject,
                    onValueChange = viewModel::onSubjectChange,
                    label = { Text(stringResource(R.string.compose_subject)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                AttachmentsSection(
                    // Inline images live in the body (as tokens), not as separate attachment chips.
                    attachments = state.attachments.filterNot { it.isInline },
                    highlight = state.highlightAttach,
                    onHighlightShown = viewModel::consumeAttachHighlight,
                    onAttach = { attachmentPicker.launch(arrayOf("*/*")) },
                    onRemove = viewModel::removeAttachment,
                )
                Spacer(Modifier.height(8.dp))
                RichTextBodyField(
                    body = state.body,
                    bodyHtml = state.bodyHtml,
                    onBodyChange = viewModel::onBodyChange,
                    label = stringResource(R.string.compose_body),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    resolveFont = FontRegistry::resolveFontFamily,
                    onPickImage = { imagePicker.launch(arrayOf("image/*")) },
                    pendingImage = state.pendingInlineImage,
                    onImageInserted = viewModel::onInlineImageInserted,
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

    if (state.showAttachmentPrompt) {
        AttachmentPromptDialog(
            onAttach = viewModel::attachInstead,
            onSendAnyway = viewModel::sendAnyway,
            onDismiss = viewModel::dismissAttachmentPrompt,
        )
    }
}

/**
 * Shown when Send is tapped on a message that mentions an attachment but carries none. "Yes"
 * returns to composing with the attach button highlighted; "No" sends the message as-is.
 */
@Composable
private fun AttachmentPromptDialog(onAttach: () -> Unit, onSendAnyway: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.confirm_attachment_title)) },
        text = { Text(stringResource(R.string.confirm_attachment_text)) },
        confirmButton = { TextButton(onClick = onAttach) { Text(stringResource(R.string.action_yes)) } },
        dismissButton = { TextButton(onClick = onSendAnyway) { Text(stringResource(R.string.action_no)) } },
    )
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
                            onClick = {
                                onSelect(account.id)
                                open = false
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Cc/Bcc start collapsed into link buttons under the To box so the body gets the vertical space.
 * A field expands when its link is tapped (taking focus), or by itself once it carries recipients
 * (reply-all/mailto prefill, a resumed draft). Once shown, a field never re-collapses — emptying
 * it mid-edit must not make it vanish.
 */
@Composable
private fun CcBccFields(cc: String, onCcChange: (String) -> Unit, bcc: String, onBccChange: (String) -> Unit) {
    var ccShown by rememberSaveable { mutableStateOf(cc.isNotBlank()) }
    var bccShown by rememberSaveable { mutableStateOf(bcc.isNotBlank()) }
    LaunchedEffect(cc, bcc) {
        if (cc.isNotBlank()) ccShown = true
        if (bcc.isNotBlank()) bccShown = true
    }
    val ccFocus = remember { FocusRequester() }
    val bccFocus = remember { FocusRequester() }
    // Deliberately not saveable: only a link tap moves focus, never rotation or a loading draft.
    var pendingFocus by remember { mutableStateOf<FocusRequester?>(null) }
    LaunchedEffect(pendingFocus) {
        pendingFocus?.requestFocus()
        pendingFocus = null
    }

    if (ccShown) {
        Spacer(Modifier.height(8.dp))
        RecipientField(cc, onCcChange, R.string.compose_cc, ccFocus)
    }
    if (bccShown) {
        Spacer(Modifier.height(8.dp))
        RecipientField(bcc, onBccChange, R.string.compose_bcc, bccFocus)
    }
    if (!ccShown || !bccShown) {
        Row {
            if (!ccShown) {
                TextButton(
                    onClick = {
                        ccShown = true
                        pendingFocus = ccFocus
                    },
                ) {
                    Text(stringResource(R.string.compose_cc))
                }
            }
            if (!bccShown) {
                TextButton(
                    onClick = {
                        bccShown = true
                        pendingFocus = bccFocus
                    },
                ) {
                    Text(stringResource(R.string.compose_bcc))
                }
            }
        }
    }
}

@Composable
private fun RecipientField(
    value: String,
    onValueChange: (String) -> Unit,
    @StringRes labelRes: Int,
    focusRequester: FocusRequester,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(labelRes)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachmentsSection(
    attachments: List<OutgoingAttachment>,
    highlight: Boolean,
    onHighlightShown: () -> Unit,
    onAttach: () -> Unit,
    onRemove: (String) -> Unit,
) {
    // Answering "Yes" on the attachment prompt lands back here: pulse the attach button a few
    // times to draw the eye, then report the highlight as consumed.
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(highlight) {
        if (highlight) {
            repeat(3) {
                pulse.animateTo(1f, tween(durationMillis = 300))
                pulse.animateTo(0f, tween(durationMillis = 300))
            }
            onHighlightShown()
        } else {
            pulse.snapTo(0f)
        }
    }
    Column(Modifier.fillMaxWidth()) {
        TextButton(
            onClick = onAttach,
            colors = ButtonDefaults.textButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = pulse.value),
            ),
        ) {
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
