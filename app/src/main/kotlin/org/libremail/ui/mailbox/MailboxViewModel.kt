// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.mailbox

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.libremail.data.sync.Syncer
import org.libremail.domain.model.Account
import org.libremail.domain.model.Folder
import org.libremail.domain.model.FolderRole
import org.libremail.domain.model.Message
import org.libremail.domain.model.ReplyMode
import org.libremail.domain.repository.AccountRepository
import org.libremail.domain.repository.MailRepository
import org.libremail.ui.navigation.Routes
import javax.inject.Inject

const val INBOX = "INBOX"

private const val SEARCH_DEBOUNCE_MS = 400L
private const val MIN_SEARCH_LENGTH = 2

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class MailboxViewModel @Inject constructor(
    private val mailRepository: MailRepository,
    accountRepository: AccountRepository,
    private val mailSyncer: Syncer,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    // Optional "open filtered to this account" arg — set when onboarding lands the user on the
    // first account they added, so the mailbox opens that account's inbox rather than the unified view.
    private val initialAccountId: String? =
        savedStateHandle.get<String>(Routes.MAILBOX_ARG_ACCOUNT)?.takeIf { it.isNotBlank() }

    val accounts: StateFlow<List<Account>> = accountRepository.observeAccounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val hasAccounts: StateFlow<Boolean> = accounts
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** null = unified "All inboxes"; otherwise the account whose mail is shown. */
    private val _selectedAccountId = MutableStateFlow(initialAccountId)
    val selectedAccountId: StateFlow<String?> = _selectedAccountId.asStateFlow()

    /** The folder whose mail is shown (always a concrete folder; defaults to the inbox). */
    private val _selectedFolder = MutableStateFlow(INBOX)
    val selectedFolder: StateFlow<String> = _selectedFolder.asStateFlow()

    /** Which account's folders the drawer lists. null follows the mailbox selection / first account. */
    private val explicitDrawerAccountId = MutableStateFlow(initialAccountId)

    /** The account the drawer is browsing: explicit drawer pick, else the filtered account, else the first. */
    val drawerAccount: StateFlow<Account?> =
        combine(accounts, explicitDrawerAccountId, _selectedAccountId) { accts, drawerId, selId ->
            accts.firstOrNull { it.id == (drawerId ?: selId) } ?: accts.firstOrNull()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The drawer account's cached folders, always including an inbox entry even before the first refresh. */
    val folders: StateFlow<List<Folder>> = drawerAccount
        .flatMapLatest { account ->
            if (account == null) {
                flowOf(emptyList())
            } else {
                mailRepository.observeFolders(account.id).map { withInbox(account.id, it) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _searchActive = MutableStateFlow(false)
    val searchActive: StateFlow<Boolean> = _searchActive.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val messages: StateFlow<List<Message>> =
        combine(
            mailRepository.observeMessages(),
            _selectedAccountId,
            _selectedFolder,
            _searchQuery,
        ) { all, accountId, folder, query ->
            val q = query.trim()
            all.filter { message ->
                (accountId == null || message.accountId == accountId) &&
                    message.folder == folder &&
                    // Outside of search show only synced rows; while searching show every match in this
                    // folder, including transient server-search hits that aren't synced.
                    (if (q.isEmpty()) message.inInbox else message.matchesSearch(q))
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val draftCount: StateFlow<Int> = mailRepository.observeDrafts()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val outboxCount: StateFlow<Int> = mailRepository.observeOutbox()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // --- Multi-select contextual action bar ---

    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedIds: StateFlow<Set<String>> = _selectedIds.asStateFlow()

    private val _pendingConfirm = MutableStateFlow<PendingAction?>(null)
    val pendingConfirm: StateFlow<PendingAction?> = _pendingConfirm.asStateFlow()

    /** True while a reply/forward draft is being fetched and built (a brief network round-trip). */
    private val _actionInProgress = MutableStateFlow(false)
    val actionInProgress: StateFlow<Boolean> = _actionInProgress.asStateFlow()

    private val _events = Channel<MailboxEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** The role of the folder currently shown — decides whether "Delete" trashes or permanently expunges. */
    val currentFolderRole: StateFlow<FolderRole?> =
        combine(folders, _selectedFolder) { fs, sel -> fs.firstOrNull { it.fullName == sel }?.role }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The single account every selected message belongs to, or null if the selection spans accounts. */
    private val selectionAccountId: StateFlow<String?> =
        combine(_selectedIds, messages) { ids, msgs ->
            msgs.filter { it.id in ids }.map { it.accountId }.distinct().singleOrNull()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Whether "Move" is offered: only when the whole selection sits in a single account's folder tree. */
    val canMove: StateFlow<Boolean> = selectionAccountId
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Destination folders for the Move picker: the selection account's selectable folders. */
    val moveTargetFolders: StateFlow<List<Folder>> = selectionAccountId
        .flatMapLatest { acct -> if (acct == null) flowOf(emptyList()) else mailRepository.observeFolders(acct) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun startSelection(id: String) {
        _selectedIds.value = setOf(id)
    }

    fun toggleSelection(id: String) {
        _selectedIds.value = _selectedIds.value.let { if (id in it) it - id else it + id }
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    fun selectAll() {
        _selectedIds.value = messages.value.map { it.id }.toSet()
    }

    fun archiveSelected() = runOnSelection { mailRepository.archive(it) }

    fun moveSelected(destFolderFullName: String) = runOnSelection {
        mailRepository.moveToFolder(it, destFolderFullName)
    }

    /** Reply and Forward open compose directly; Reply All is confirmed first (see [requestReplyAll]). */
    fun reply(mode: ReplyMode) {
        val id = _selectedIds.value.singleOrNull() ?: return
        buildReply(mode, id)
    }

    // Confirmation-gated actions: the screen shows a dialog bound to [pendingConfirm], then [confirmPending].

    fun requestSpam() {
        if (_selectedIds.value.isEmpty()) return
        _pendingConfirm.value = PendingAction.Spam(_selectedIds.value.size)
    }

    fun requestDelete() {
        if (_selectedIds.value.isEmpty()) return
        val role = currentFolderRole.value
        val permanent = role == FolderRole.TRASH || role == FolderRole.SPAM
        _pendingConfirm.value = PendingAction.Delete(_selectedIds.value.size, permanent)
    }

    fun requestReplyAll() {
        val id = _selectedIds.value.singleOrNull() ?: return
        _pendingConfirm.value = PendingAction.ReplyAll(id)
    }

    fun confirmPending() {
        when (val pending = _pendingConfirm.value) {
            is PendingAction.Spam -> runOnSelection { mailRepository.reportSpam(it) }
            is PendingAction.Delete ->
                if (pending.permanent) {
                    runOnSelection { mailRepository.expunge(it) }
                } else {
                    runOnSelection { mailRepository.trash(it) }
                }
            is PendingAction.ReplyAll -> buildReply(ReplyMode.REPLY_ALL, pending.messageId)
            null -> Unit
        }
        _pendingConfirm.value = null
    }

    fun dismissConfirm() {
        _pendingConfirm.value = null
    }

    /** Snapshots the selection, exits selection mode, then runs [block]; failures surface via [error]. */
    private fun runOnSelection(block: suspend (List<String>) -> Result<Unit>) {
        val ids = _selectedIds.value.toList()
        if (ids.isEmpty()) return
        clearSelection()
        viewModelScope.launch {
            block(ids).onFailure { _error.value = it.message ?: "Action failed" }
        }
    }

    private fun buildReply(mode: ReplyMode, messageId: String) {
        clearSelection()
        viewModelScope.launch {
            _actionInProgress.value = true
            mailRepository.buildReplyDraft(messageId, mode).fold(
                onSuccess = { draftId -> _events.send(MailboxEvent.OpenCompose(draftId)) },
                onFailure = { _error.value = it.message ?: "Could not open compose" },
            )
            _actionInProgress.value = false
        }
    }

    init {
        // Fall back to the unified inbox if the filtered account is removed. The list.isNotEmpty()
        // guard avoids clobbering a seeded account filter during the initial empty emission (before
        // the account list first loads from the database).
        viewModelScope.launch {
            accounts.collect { list ->
                val selected = _selectedAccountId.value
                if (selected != null && list.isNotEmpty() && list.none { it.id == selected }) {
                    _selectedAccountId.value = null
                    _selectedFolder.value = INBOX
                }
            }
        }
        // Server-side search: fetch matches for the current folder into the cache; the list filter
        // then surfaces them.
        viewModelScope.launch {
            _searchQuery
                .debounce(SEARCH_DEBOUNCE_MS)
                .map { it.trim() }
                .filter { it.length >= MIN_SEARCH_LENGTH }
                .distinctUntilChanged()
                .collect { query ->
                    mailRepository.searchServer(query, _selectedAccountId.value, _selectedFolder.value)
                }
        }
    }

    fun selectAccount(accountId: String?) {
        clearSelection()
        _selectedAccountId.value = accountId
    }

    /** Browses a specific account's folder; syncs it from the server in the background. */
    fun selectFolder(accountId: String, folderFullName: String) {
        clearSelection()
        _selectedAccountId.value = accountId
        explicitDrawerAccountId.value = accountId
        _selectedFolder.value = folderFullName
        viewModelScope.launch { mailSyncer.syncFolder(accountId, folderFullName) }
    }

    /** Returns to the unified inbox across all accounts. */
    fun selectUnifiedInbox() {
        clearSelection()
        _selectedAccountId.value = null
        explicitDrawerAccountId.value = null
        _selectedFolder.value = INBOX
    }

    /** Points the drawer at another account's folders (without changing the shown mail yet). */
    fun setDrawerAccount(accountId: String) {
        explicitDrawerAccountId.value = accountId
        viewModelScope.launch { mailRepository.refreshFolders(accountId) }
    }

    /** Refreshes the current drawer account's folder list (called when the drawer opens). */
    fun onDrawerOpened() {
        val accountId = drawerAccount.value?.id ?: return
        viewModelScope.launch { mailRepository.refreshFolders(accountId) }
    }

    fun openSearch() {
        clearSelection()
        _searchActive.value = true
    }

    fun closeSearch() {
        _searchActive.value = false
        _searchQuery.value = ""
        // Drop the transient server-search hits so they don't linger in the folder.
        viewModelScope.launch { mailRepository.clearSearchResults() }
    }

    fun onSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun refresh() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            val accountId = _selectedAccountId.value
            val folder = _selectedFolder.value
            // The unified inbox refreshes every account; a specific folder refreshes just that folder.
            val result = if (accountId == null && folder == INBOX) {
                mailSyncer.syncAll()
            } else if (accountId != null) {
                mailSyncer.syncFolder(accountId, folder)
            } else {
                mailSyncer.syncAll()
            }
            result.onFailure { _error.value = it.message ?: "Sync failed" }
            _isRefreshing.value = false
        }
    }

    fun consumeError() {
        _error.value = null
    }
}

/** A destructive or notable CAB action awaiting user confirmation. */
sealed interface PendingAction {
    data class Spam(val count: Int) : PendingAction
    data class Delete(val count: Int, val permanent: Boolean) : PendingAction
    data class ReplyAll(val messageId: String) : PendingAction
}

/** One-shot effects the mailbox screen acts on. */
sealed interface MailboxEvent {
    data class OpenCompose(val draftId: String) : MailboxEvent
}

/** Guarantees an inbox entry so the drawer is usable before the first folder-list refresh completes. */
private fun withInbox(accountId: String, folders: List<Folder>): List<Folder> =
    if (folders.any { it.fullName.equals(INBOX, ignoreCase = true) }) {
        folders
    } else {
        listOf(Folder(accountId, INBOX, INBOX, FolderRole.INBOX, selectable = true)) + folders
    }

/** Local match over the always-populated header fields (and snippet, once a body is cached). */
private fun Message.matchesSearch(query: String): Boolean = sender.contains(query, ignoreCase = true) ||
    senderEmail.contains(query, ignoreCase = true) ||
    subject.contains(query, ignoreCase = true) ||
    snippet.contains(query, ignoreCase = true)
