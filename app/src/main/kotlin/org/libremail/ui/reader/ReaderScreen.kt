// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.reader

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.libremail.R
import org.libremail.domain.model.Attachment
import org.libremail.domain.model.InlineImage
import org.libremail.domain.model.Message
import org.libremail.domain.model.ReplyMode
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    onBack: () -> Unit,
    onOpenCompose: (draftId: String) -> Unit,
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val noAppMessage = stringResource(R.string.attachment_no_app)
    val downloadFailedTemplate = stringResource(R.string.attachment_download_failed)
    val replyFailedMessage = stringResource(R.string.reader_reply_failed)

    LaunchedEffect(state.deleted) {
        if (state.deleted) onBack()
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ReaderEvent.OpenFile ->
                    if (!openAttachment(context, event.file, event.mimeType)) {
                        snackbarHostState.showSnackbar(noAppMessage)
                    }

                is ReaderEvent.DownloadFailed ->
                    snackbarHostState.showSnackbar(downloadFailedTemplate.format(event.name))

                is ReaderEvent.OpenCompose -> onOpenCompose(event.draftId)

                is ReaderEvent.ComposeFailed ->
                    snackbarHostState.showSnackbar(event.message ?: replyFailedMessage)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_reader)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    val message = state.message
                    if (message != null) {
                        TextButton(
                            onClick = { viewModel.reply(ReplyMode.REPLY) },
                            enabled = !state.composing,
                        ) {
                            Text(stringResource(R.string.reader_reply))
                        }
                        ReplyOverflow(
                            enabled = !state.composing,
                            onReplyAll = { viewModel.reply(ReplyMode.REPLY_ALL) },
                            onForward = { viewModel.reply(ReplyMode.FORWARD) },
                        )
                        IconButton(onClick = viewModel::toggleStar) {
                            Icon(
                                Icons.Filled.Star,
                                contentDescription = stringResource(R.string.reader_star),
                                tint = if (message.isStarred) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        IconButton(onClick = viewModel::delete) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.reader_delete))
                        }
                    }
                },
            )
        },
    ) { padding ->
        val message = state.message
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            message != null -> MessageBody(
                message = message,
                attachments = state.attachments,
                inlineImages = state.inlineImages,
                downloading = state.downloading,
                downloaded = state.downloaded,
                onDownloadAttachment = viewModel::downloadAttachment,
                loadRemoteImages = state.loadRemoteImages,
                onLoadRemoteImages = viewModel::loadRemoteImages,
                contentPadding = padding,
            )

            else -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(state.error ?: stringResource(R.string.reader_empty))
            }
        }
    }
}

/**
 * Overflow menu holding the reader's secondary reply actions — Reply All and Forward — so the app bar
 * keeps Reply as its one prominent action (#303). Disabled while a draft is being built so a rapid tap
 * can't kick off a second one before the first navigates to compose.
 */
@Composable
private fun ReplyOverflow(enabled: Boolean, onReplyAll: () -> Unit, onForward: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }, enabled = enabled) {
        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_reply_all)) },
            onClick = {
                expanded = false
                onReplyAll()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_forward)) },
            onClick = {
                expanded = false
                onForward()
            },
        )
    }
}

@Composable
private fun MessageBody(
    message: Message,
    attachments: List<Attachment>,
    inlineImages: Map<String, InlineImage>,
    downloading: Set<Int>,
    downloaded: Set<Int>,
    onDownloadAttachment: (Attachment) -> Unit,
    loadRemoteImages: Boolean,
    onLoadRemoteImages: () -> Unit,
    contentPadding: PaddingValues,
) {
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        Header(message)
        HorizontalDivider()
        if (attachments.isNotEmpty()) {
            Attachments(attachments, downloading, downloaded, onDownloadAttachment)
            HorizontalDivider()
        }
        if (message.isHtml && !loadRemoteImages) {
            RemoteImagesBanner(onLoadRemoteImages)
        }
        when {
            message.body.isBlank() -> Text(
                stringResource(R.string.reader_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )

            message.isHtml -> HtmlBody(
                html = message.body,
                loadRemoteImages = loadRemoteImages,
                inlineImages = inlineImages,
                modifier = Modifier.fillMaxSize(),
            )

            else -> SelectionContainer(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = message.body,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun Attachments(
    attachments: List<Attachment>,
    downloading: Set<Int>,
    downloaded: Set<Int>,
    onDownload: (Attachment) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            stringResource(R.string.attachments_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        // The first attachment always shows. Any extras collapse behind an accordion so a message
        // with many attachments can't push its body off-screen (#134).
        val first = attachments.first()
        AttachmentRow(
            attachment = first,
            downloading = first.partIndex in downloading,
            downloaded = first.partIndex in downloaded,
            onClick = { onDownload(first) },
        )
        Spacer(Modifier.height(8.dp))
        val extras = attachments.drop(1)
        if (extras.isNotEmpty()) {
            var expanded by rememberSaveable { mutableStateOf(false) }
            AttachmentsToggle(
                extraCount = extras.size,
                expanded = expanded,
                onToggle = { expanded = !expanded },
            )
            AnimatedVisibility(visible = expanded) {
                Column {
                    extras.forEach { attachment ->
                        Spacer(Modifier.height(8.dp))
                        AttachmentRow(
                            attachment = attachment,
                            downloading = attachment.partIndex in downloading,
                            downloaded = attachment.partIndex in downloaded,
                            onClick = { onDownload(attachment) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Collapsed-by-default control that reveals the 2nd..Nth attachments. It is a single clickable
 * [Role.Button] whose label ("See x more attachments" / "See fewer attachments") and rotating
 * chevron expose the expanded state to screen readers.
 */
@Composable
private fun AttachmentsToggle(extraCount: Int, expanded: Boolean, onToggle: () -> Unit) {
    val label = if (expanded) {
        stringResource(R.string.attachments_see_fewer)
    } else {
        pluralStringResource(R.plurals.attachments_see_more, extraCount, extraCount)
    }
    val chevronDescription = stringResource(
        if (expanded) R.string.attachments_collapse else R.string.attachments_expand,
    )
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "attachmentsChevronRotation",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = chevronDescription,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.rotate(rotation),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun AttachmentRow(attachment: Attachment, downloading: Boolean, downloaded: Boolean, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !downloading, onClick = onClick),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                if (downloading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        text = fileExtension(attachment.filename),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    attachment.filename,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val size = formatSize(attachment.sizeBytes)
                if (size.isNotEmpty()) {
                    Text(
                        size,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (downloaded) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Filled.Check,
                    contentDescription = stringResource(R.string.attachment_downloaded),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun Header(message: Message) {
    Column(Modifier.padding(16.dp)) {
        Text(message.subject, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = message.sender.trim().firstOrNull()?.uppercase() ?: "?",
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(message.sender, style = MaterialTheme.typography.titleMedium)
                Text(
                    message.senderEmail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RemoteImagesBanner(onLoadRemoteImages: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.reader_images_blocked),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onLoadRemoteImages) {
            Text(stringResource(R.string.reader_show_images))
        }
    }
}

/** Launches a viewer for the downloaded file via a FileProvider URI. Returns false if no app handles it. */
private fun openAttachment(context: Context, file: File, mimeType: String): Boolean {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType.ifBlank { "*/*" })
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

private const val MAX_EXTENSION_LENGTH = 4
private const val BYTES_PER_KB = 1024.0

private fun fileExtension(filename: String): String =
    filename.substringAfterLast('.', "").uppercase().take(MAX_EXTENSION_LENGTH).ifBlank {
        "FILE"
    }

private fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < BYTES_PER_KB -> "$bytes B"
    bytes < BYTES_PER_KB * BYTES_PER_KB -> "%.0f KB".format(bytes / BYTES_PER_KB)
    else -> "%.1f MB".format(bytes / (BYTES_PER_KB * BYTES_PER_KB))
}
