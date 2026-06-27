// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.mailbox

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.libremail.R
import org.libremail.domain.model.Account
import org.libremail.domain.model.Message

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailboxScreen(
    onOpenMessage: (String) -> Unit,
    onCompose: () -> Unit,
    onOpenDrafts: () -> Unit,
    onAddAccount: () -> Unit,
    onSelectTab: (org.libremail.ui.TopDest) -> Unit,
    viewModel: MailboxViewModel = hiltViewModel(),
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val selectedAccountId by viewModel.selectedAccountId.collectAsStateWithLifecycle()
    val hasAccounts by viewModel.hasAccounts.collectAsStateWithLifecycle()
    val draftCount by viewModel.draftCount.collectAsStateWithLifecycle()
    val searchActive by viewModel.searchActive.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    BackHandler(enabled = searchActive) { viewModel.closeSearch() }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searchActive) {
                        SearchField(query = searchQuery, onQueryChange = viewModel::onSearchQuery)
                    } else {
                        Text(stringResource(R.string.title_mailbox))
                    }
                },
                navigationIcon = {
                    if (searchActive) {
                        IconButton(onClick = viewModel::closeSearch) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.search_close))
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
                NoAccountState(onAddAccount = onAddAccount)
            } else {
                val accountsById = remember(accounts) { accounts.associateBy { it.id } }
                val showAccount = selectedAccountId == null && accounts.size >= 2
                Column(Modifier.fillMaxSize()) {
                    if (accounts.size >= 2) {
                        AccountFilterRow(
                            accounts = accounts,
                            selectedId = selectedAccountId,
                            onSelect = viewModel::selectAccount,
                        )
                    }
                    if (draftCount > 0 && !searchActive) {
                        DraftsEntry(count = draftCount, onClick = onOpenDrafts)
                        HorizontalDivider()
                    }
                    PullToRefreshBox(
                        isRefreshing = isRefreshing,
                        onRefresh = viewModel::refresh,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            if (messages.isEmpty()) {
                                item {
                                    if (searchActive && searchQuery.isNotBlank()) {
                                        NoResultsState(Modifier.fillParentMaxSize())
                                    } else {
                                        NoMessagesState(Modifier.fillParentMaxSize())
                                    }
                                }
                            } else {
                                items(messages, key = { it.id }) { message ->
                                    MessageRow(
                                        message = message,
                                        accountLabel = if (showAccount) accountsById[message.accountId]?.email else null,
                                        onClick = { onOpenMessage(message.id) },
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
private fun AccountFilterRow(accounts: List<Account>, selectedId: String?, onSelect: (String?) -> Unit) {
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
                label = { Text(account.email, maxLines = 1) },
            )
        }
    }
}

@Composable
private fun MessageRow(message: Message, accountLabel: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(message.sender)
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
                Text(
                    text = formatTimestamp(message.timestampMillis),
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
private fun NoAccountState(onAddAccount: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.Email,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.mailbox_welcome_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.mailbox_welcome_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAddAccount) {
            Text(stringResource(R.string.settings_add_account))
        }
    }
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

private fun formatTimestamp(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(
        millis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()
