// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.mailbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.libremail.data.sync.MailSyncer
import org.libremail.domain.model.Account
import org.libremail.domain.model.Folder
import org.libremail.domain.model.FolderRole
import org.libremail.domain.model.Message
import org.libremail.domain.repository.AccountRepository
import org.libremail.domain.repository.MailRepository

const val INBOX = "INBOX"

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class MailboxViewModel @Inject constructor(
    private val mailRepository: MailRepository,
    accountRepository: AccountRepository,
    private val mailSyncer: MailSyncer,
) : ViewModel() {

    val accounts: StateFlow<List<Account>> = accountRepository.observeAccounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val hasAccounts: StateFlow<Boolean> = accounts
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** null = unified "All inboxes"; otherwise the account whose mail is shown. */
    private val _selectedAccountId = MutableStateFlow<String?>(null)
    val selectedAccountId: StateFlow<String?> = _selectedAccountId.asStateFlow()

    /** The folder whose mail is shown (always a concrete folder; defaults to the inbox). */
    private val _selectedFolder = MutableStateFlow(INBOX)
    val selectedFolder: StateFlow<String> = _selectedFolder.asStateFlow()

    /** Which account's folders the drawer lists. null follows the mailbox selection / first account. */
    private val _drawerAccountId = MutableStateFlow<String?>(null)

    /** The account the drawer is browsing: explicit drawer pick, else the filtered account, else the first. */
    val drawerAccount: StateFlow<Account?> =
        combine(accounts, _drawerAccountId, _selectedAccountId) { accts, drawerId, selId ->
            accts.firstOrNull { it.id == (drawerId ?: selId) } ?: accts.firstOrNull()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The drawer account's cached folders, always including an inbox entry even before the first refresh. */
    val folders: StateFlow<List<Folder>> = drawerAccount
        .flatMapLatest { account ->
            if (account == null) flowOf(emptyList())
            else mailRepository.observeFolders(account.id).map { withInbox(account.id, it) }
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

    init {
        // Fall back to the unified inbox if the filtered account is removed.
        viewModelScope.launch {
            accounts.collect { list ->
                val selected = _selectedAccountId.value
                if (selected != null && list.none { it.id == selected }) {
                    _selectedAccountId.value = null
                    _selectedFolder.value = INBOX
                }
            }
        }
        // Server-side search: fetch matches for the current folder into the cache; the list filter
        // then surfaces them.
        viewModelScope.launch {
            _searchQuery
                .debounce(400L)
                .map { it.trim() }
                .filter { it.length >= 2 }
                .distinctUntilChanged()
                .collect { query ->
                    mailRepository.searchServer(query, _selectedAccountId.value, _selectedFolder.value)
                }
        }
    }

    fun selectAccount(accountId: String?) {
        _selectedAccountId.value = accountId
    }

    /** Browses a specific account's folder; syncs it from the server in the background. */
    fun selectFolder(accountId: String, folderFullName: String) {
        _selectedAccountId.value = accountId
        _drawerAccountId.value = accountId
        _selectedFolder.value = folderFullName
        viewModelScope.launch { mailSyncer.syncFolder(accountId, folderFullName) }
    }

    /** Returns to the unified inbox across all accounts. */
    fun selectUnifiedInbox() {
        _selectedAccountId.value = null
        _drawerAccountId.value = null
        _selectedFolder.value = INBOX
    }

    /** Points the drawer at another account's folders (without changing the shown mail yet). */
    fun setDrawerAccount(accountId: String) {
        _drawerAccountId.value = accountId
        viewModelScope.launch { mailRepository.refreshFolders(accountId) }
    }

    /** Refreshes the current drawer account's folder list (called when the drawer opens). */
    fun onDrawerOpened() {
        val accountId = drawerAccount.value?.id ?: return
        viewModelScope.launch { mailRepository.refreshFolders(accountId) }
    }

    fun openSearch() {
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

/** Guarantees an inbox entry so the drawer is usable before the first folder-list refresh completes. */
private fun withInbox(accountId: String, folders: List<Folder>): List<Folder> =
    if (folders.any { it.fullName.equals(INBOX, ignoreCase = true) }) {
        folders
    } else {
        listOf(Folder(accountId, INBOX, INBOX, FolderRole.INBOX, selectable = true)) + folders
    }

/** Local match over the always-populated header fields (and snippet, once a body is cached). */
private fun Message.matchesSearch(query: String): Boolean =
    sender.contains(query, ignoreCase = true) ||
        senderEmail.contains(query, ignoreCase = true) ||
        subject.contains(query, ignoreCase = true) ||
        snippet.contains(query, ignoreCase = true)
