// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.mailbox

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import kotlinx.coroutines.launch
import org.libremail.R
import org.libremail.domain.model.Account
import org.libremail.domain.model.Folder
import org.libremail.domain.model.FolderRole
import org.libremail.domain.model.Message
import org.libremail.domain.model.ReplyMode
import org.libremail.ui.onboarding.WelcomeContent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailboxScreen(
    onOpenMessage: (String) -> Unit,
    onCompose: () -> Unit,
    onOpenDrafts: () -> Unit,
    onOpenOutbox: () -> Unit,
    onAddAccount: () -> Unit,
    onOpenCompose: (String) -> Unit,
    onSelectTab: (org.libremail.ui.TopDest) -> Unit,
    viewModel: MailboxViewModel = hiltViewModel(),
) {
    // Every mailbox mode — unified/per-account browse and search — is a single paged list now
    // (issues #124, #214); the ViewModel dispatches the right pager by (account, folder, query).
    val pagedMessages = viewModel.pagedMessages.collectAsLazyPagingItems()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val selectedAccountId by viewModel.selectedAccountId.collectAsStateWithLifecycle()
    val selectedFolder by viewModel.selectedFolder.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val folderUnreadCounts by viewModel.folderUnreadCounts.collectAsStateWithLifecycle()
    val accountsWithUnread by viewModel.accountsWithUnread.collectAsStateWithLifecycle()
    val drawerAccount by viewModel.drawerAccount.collectAsStateWithLifecycle()
    val hasAccounts by viewModel.hasAccounts.collectAsStateWithLifecycle()
    val draftCount by viewModel.draftCount.collectAsStateWithLifecycle()
    val outboxCount by viewModel.outboxCount.collectAsStateWithLifecycle()
    val searchActive by viewModel.searchActive.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val isSyncingFolder by viewModel.isSyncingFolder.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
    val pendingConfirm by viewModel.pendingConfirm.collectAsStateWithLifecycle()
    val currentFolderRole by viewModel.currentFolderRole.collectAsStateWithLifecycle()
    val canMove by viewModel.canMove.collectAsStateWithLifecycle()
    val moveTargetFolders by viewModel.moveTargetFolders.collectAsStateWithLifecycle()
    val actionInProgress by viewModel.actionInProgress.collectAsStateWithLifecycle()
    val selectionMode = selectedIds.isNotEmpty()
    var showMovePicker by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    BackHandler(enabled = searchActive) { viewModel.closeSearch() }
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
    // Registered last so it takes priority: Back exits selection before closing search/drawer.
    BackHandler(enabled = selectionMode) { viewModel.clearSelection() }
    LaunchedEffect(drawerState.isOpen) { if (drawerState.isOpen) viewModel.onDrawerOpened() }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeError()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is MailboxEvent.OpenCompose -> onOpenCompose(event.draftId)
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen || (hasAccounts && !searchActive && !selectionMode),
        drawerContent = {
            ModalDrawerSheet {
                FolderDrawer(
                    accounts = accounts,
                    drawerAccount = drawerAccount,
                    folders = folders,
                    folderUnreadCounts = folderUnreadCounts,
                    accountsWithUnread = accountsWithUnread,
                    selectedAccountId = selectedAccountId,
                    selectedFolder = selectedFolder,
                    onSelectUnifiedInbox = {
                        viewModel.selectUnifiedInbox()
                        scope.launch { drawerState.close() }
                    },
                    onSelectFolder = { accountId, folder ->
                        viewModel.selectFolder(accountId, folder)
                        scope.launch { drawerState.close() }
                    },
                    onSelectDrawerAccount = viewModel::setDrawerAccount,
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                if (selectionMode) {
                    SelectionTopBar(
                        count = selectedIds.size,
                        folderRole = currentFolderRole,
                        canMove = canMove,
                        onClose = viewModel::clearSelection,
                        onArchive = viewModel::archiveSelected,
                        onDelete = viewModel::requestDelete,
                        onSpam = viewModel::requestSpam,
                        onMove = { showMovePicker = true },
                        onSelectAll = {
                            // "Select all" acts on what's shown: the currently loaded window of the
                            // paged list (issues #124, #214).
                            viewModel.selectAll(pagedMessages.itemSnapshotList.items)
                        },
                        onReply = { viewModel.reply(ReplyMode.REPLY) },
                        onReplyAll = viewModel::requestReplyAll,
                        onForward = { viewModel.reply(ReplyMode.FORWARD) },
                    )
                } else {
                    TopAppBar(
                        title = {
                            if (searchActive) {
                                SearchField(query = searchQuery, onQueryChange = viewModel::onSearchQuery)
                            } else {
                                val current = folders.firstOrNull { it.fullName == selectedFolder }
                                Text(
                                    if (current != null) {
                                        // The drawer's de-duplicated label, so "Drafts - Gmail"
                                        // doesn't collapse to an ambiguous "Drafts" once opened.
                                        resolvedFolderLabels(folders, accounts)[current.fullName]
                                            ?: folderDisplayLabel(current)
                                    } else {
                                        stringResource(R.string.title_mailbox)
                                    },
                                )
                            }
                        },
                        navigationIcon = {
                            if (searchActive) {
                                IconButton(onClick = viewModel::closeSearch) {
                                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.search_close))
                                }
                            } else if (hasAccounts) {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.drawer_open))
                                }
                            }
                        },
                        actions = {
                            if (hasAccounts && !searchActive) {
                                IconButton(onClick = viewModel::openSearch) {
                                    Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search))
                                }
                            }
                        },
                    )
                }
            },
            bottomBar = {
                org.libremail.ui.LibreMailBottomBar(
                    current = org.libremail.ui.TopDest.MAILBOX,
                    onSelect = onSelectTab,
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = onCompose,
                    icon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    text = { Text(stringResource(R.string.action_compose)) },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (!hasAccounts) {
                    // Onboarding covers the fresh-install empty case; this is the runtime fallback
                    // (e.g. the last account was removed). Reuses the same welcome invitation.
                    WelcomeContent(onAddAccount = onAddAccount, modifier = Modifier.fillMaxSize())
                } else {
                    val accountsById = remember(accounts) { accounts.associateBy { it.id } }
                    val showAccount = selectedAccountId == null && accounts.size >= 2
                    Column(Modifier.fillMaxSize()) {
                        if (accounts.size >= 2 && selectedFolder == INBOX) {
                            AccountFilterRow(
                                accounts = accounts,
                                selectedId = selectedAccountId,
                                accountsWithUnread = accountsWithUnread,
                                onSelect = viewModel::selectAccount,
                            )
                        }
                        if (draftCount > 0 && !searchActive && selectedFolder == INBOX) {
                            DraftsEntry(count = draftCount, onClick = onOpenDrafts)
                            HorizontalDivider()
                        }
                        if (outboxCount > 0 && !searchActive && selectedFolder == INBOX) {
                            OutboxEntry(count = outboxCount, onClick = onOpenOutbox)
                            HorizontalDivider()
                        }
                        PullToRefreshBox(
                            isRefreshing = isRefreshing,
                            onRefresh = viewModel::refresh,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                if (pagedMessages.itemCount == 0) {
                                    item {
                                        val loadState = pagedMessages.loadState
                                        // "Settled" = the refresh finished and there is no further page,
                                        // so an empty list is genuinely empty. Gating on it (not just
                                        // "refresh not Loading") holds the empty state back on an
                                        // empty→loaded transition — before the first page, or before a
                                        // search's debounced server hits land (issues #124, #214).
                                        val settled = loadState.refresh is LoadState.NotLoading &&
                                            loadState.append.endOfPaginationReached
                                        when {
                                            searchActive && searchQuery.isNotBlank() ->
                                                if (settled) NoResultsState(Modifier.fillParentMaxSize())
                                            // An uncached per-account folder still doing its initial
                                            // background sync: show the spinner rather than "No messages"
                                            // until it settles or the fetch populates rows (issue #149).
                                            isSyncingFolder ->
                                                Box(
                                                    Modifier.fillParentMaxSize(),
                                                    contentAlignment = Alignment.Center,
                                                ) { CircularProgressIndicator() }
                                            settled -> NoMessagesState(Modifier.fillParentMaxSize())
                                        }
                                    }
                                } else {
                                    items(
                                        count = pagedMessages.itemCount,
                                        key = pagedMessages.itemKey { it.id },
                                    ) { index ->
                                        val message = pagedMessages[index] ?: return@items
                                        val accountLabel =
                                            if (showAccount) accountsById[message.accountId]?.email else null
                                        MessageRow(
                                            message = message,
                                            accountLabel = accountLabel,
                                            selected = message.id in selectedIds,
                                            onClick = {
                                                if (selectionMode) {
                                                    viewModel.toggleSelection(message.id, message.accountId)
                                                } else {
                                                    onOpenMessage(message.id)
                                                }
                                            },
                                            onLongClick = { viewModel.startSelection(message.id, message.accountId) },
                                        )
                                        HorizontalDivider()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        pendingConfirm?.let { pending ->
            ConfirmActionDialog(
                pending = pending,
                onConfirm = viewModel::confirmPending,
                onDismiss = viewModel::dismissConfirm,
            )
        }
        if (showMovePicker) {
            MoveFolderDialog(
                folders = moveTargetFolders.filter { it.selectable && it.fullName != selectedFolder },
                // Labels resolved against the unfiltered list, so rows keep the drawer's
                // disambiguation even when a colliding twin (e.g. the current folder) is
                // filtered out of the picker itself.
                labels = resolvedFolderLabels(moveTargetFolders, accounts),
                onSelect = { folder ->
                    showMovePicker = false
                    viewModel.moveSelected(folder.fullName)
                },
                onDismiss = { showMovePicker = false },
            )
        }
        // Blocking spinner while a reply/forward fetches the latest message from the server.
        if (actionInProgress) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    val focusRequester = remember { FocusRequester() }
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
        decorationBox = { innerTextField ->
            if (query.isEmpty()) {
                Text(
                    stringResource(R.string.search_hint),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            innerTextField()
        },
    )
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

@Composable
private fun DraftsEntry(count: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(
            text = stringResource(R.string.drafts_count, count),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun OutboxEntry(count: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(
            text = stringResource(R.string.outbox_count, count),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun AccountFilterRow(
    accounts: List<Account>,
    selectedId: String?,
    accountsWithUnread: Set<String>,
    onSelect: (String?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selectedId == null,
            onClick = { onSelect(null) },
            label = { Text(stringResource(R.string.mailbox_all_accounts)) },
        )
        accounts.forEach { account ->
            FilterChip(
                selected = selectedId == account.id,
                onClick = { onSelect(account.id) },
                label = {
                    Text(
                        account.email,
                        maxLines = 1,
                        fontWeight = if (account.id in accountsWithUnread) FontWeight.Bold else FontWeight.Normal,
                    )
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    message: Message,
    accountLabel: String?,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) SelectedAvatar() else Avatar(message.sender)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = message.sender,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (message.isRead) FontWeight.Normal else FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                if (message.bodyFetched) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = stringResource(R.string.message_available_offline),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    // remember so the relative-time string isn't re-formatted (allocating a new String
                    // and reading System.currentTimeMillis()) on every recomposition of the row.
                    text = remember(message.timestampMillis) { formatTimestamp(message.timestampMillis) },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = message.subject,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (message.isRead) FontWeight.Normal else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (message.snippet.isNotBlank()) {
                Text(
                    text = message.snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (accountLabel != null) {
                Text(
                    text = accountLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Avatar(name: String) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.trim().firstOrNull()?.uppercase() ?: "?",
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun SelectedAvatar() {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
    }
}

/**
 * The contextual action bar shown while messages are selected. The common actions — Archive, Spam,
 * Delete — are direct icon buttons (matching the reader's icons-not-menus app bar); Archive/Spam are
 * hidden while already viewing that role's folder. The overflow keeps only the long tail: Move (no
 * usable glyph in material-icons-core) and Select all, plus Reply/Reply All/Forward for a single
 * selected message. At most four 48dp actions plus the close button fit a 320dp-wide bar; the
 * count title just truncates earlier on such screens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    count: Int,
    folderRole: FolderRole?,
    canMove: Boolean,
    onClose: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onSpam: () -> Unit,
    onMove: () -> Unit,
    onSelectAll: () -> Unit,
    onReply: () -> Unit,
    onReplyAll: () -> Unit,
    onForward: () -> Unit,
) {
    TopAppBar(
        title = { Text(stringResource(R.string.cab_selected_count, count)) },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cab_close))
            }
        },
        actions = {
            if (folderRole != FolderRole.ARCHIVE) {
                IconButton(onClick = onArchive) {
                    // material-icons-core ships no archive glyph; the checkmark leans on the
                    // "done with it = archive it" mail idiom (Google Inbox's sweep).
                    Icon(Icons.Filled.Done, contentDescription = stringResource(R.string.action_archive))
                }
            }
            if (folderRole != FolderRole.SPAM) {
                IconButton(onClick = onSpam) {
                    Icon(Icons.Filled.Warning, contentDescription = stringResource(R.string.action_spam))
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
            }
            var expanded by remember { mutableStateOf(false) }
            IconButton(onClick = { expanded = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (canMove) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_move)) },
                        onClick = {
                            expanded = false
                            onMove()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_select_all)) },
                    onClick = {
                        expanded = false
                        onSelectAll()
                    },
                )
                if (count == 1) {
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_reply)) },
                        onClick = {
                            expanded = false
                            onReply()
                        },
                    )
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
        },
    )
}

@Composable
private fun ConfirmActionDialog(pending: PendingAction, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val title: String
    val text: String
    val confirmLabel: String
    when (pending) {
        is PendingAction.Spam -> {
            title = stringResource(R.string.confirm_spam_title)
            text = stringResource(R.string.confirm_spam_text, pending.count)
            confirmLabel = stringResource(R.string.action_move)
        }
        is PendingAction.Delete -> if (pending.permanent) {
            title = stringResource(R.string.confirm_delete_title)
            text = stringResource(R.string.confirm_delete_text, pending.count)
            confirmLabel = stringResource(R.string.action_delete)
        } else {
            title = stringResource(R.string.confirm_trash_title)
            text = stringResource(R.string.confirm_trash_text, pending.count)
            confirmLabel = stringResource(R.string.action_move)
        }
        is PendingAction.ReplyAll -> {
            title = stringResource(R.string.confirm_reply_all_title)
            text = stringResource(R.string.confirm_reply_all_text)
            confirmLabel = stringResource(R.string.action_reply_all)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun MoveFolderDialog(
    folders: List<Folder>,
    labels: Map<String, String>,
    onSelect: (Folder) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.move_picker_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                folders.forEach { folder ->
                    Text(
                        text = labels[folder.fullName] ?: folderDisplayLabel(folder),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(folder) }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun NoMessagesState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.Email,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.mailbox_empty), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.mailbox_pull_to_refresh),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NoResultsState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.search_no_results), style = MaterialTheme.typography.titleMedium)
    }
}

private fun formatTimestamp(millis: Long): String = DateUtils.getRelativeTimeSpanString(
    millis,
    System.currentTimeMillis(),
    DateUtils.MINUTE_IN_MILLIS,
).toString()
