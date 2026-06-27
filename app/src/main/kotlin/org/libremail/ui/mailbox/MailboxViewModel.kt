// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.mailbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.libremail.data.sync.MailSyncer
import org.libremail.domain.model.Account
import org.libremail.domain.model.Message
import org.libremail.domain.repository.AccountRepository
import org.libremail.domain.repository.MailRepository

@HiltViewModel
class MailboxViewModel @Inject constructor(
    mailRepository: MailRepository,
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

    private val _searchActive = MutableStateFlow(false)
    val searchActive: StateFlow<Boolean> = _searchActive.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val messages: StateFlow<List<Message>> =
        combine(mailRepository.observeMessages(), _selectedAccountId, _searchQuery) { all, accountId, query ->
            val q = query.trim()
            all.filter { message ->
                (accountId == null || message.accountId == accountId) &&
                    (q.isEmpty() || message.matchesSearch(q))
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        // Fall back to the unified view if the filtered account is removed.
        viewModelScope.launch {
            accounts.collect { list ->
                val selected = _selectedAccountId.value
                if (selected != null && list.none { it.id == selected }) _selectedAccountId.value = null
            }
        }
    }

    fun selectAccount(accountId: String?) {
        _selectedAccountId.value = accountId
    }

    fun openSearch() {
        _searchActive.value = true
    }

    fun closeSearch() {
        _searchActive.value = false
        _searchQuery.value = ""
    }

    fun onSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun refresh() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            mailSyncer.syncAll().onFailure { _error.value = it.message ?: "Sync failed" }
            _isRefreshing.value = false
        }
    }

    fun consumeError() {
        _error.value = null
    }
}

/** Local match over the always-populated header fields (and snippet, once a body is cached). */
private fun Message.matchesSearch(query: String): Boolean =
    sender.contains(query, ignoreCase = true) ||
        senderEmail.contains(query, ignoreCase = true) ||
        subject.contains(query, ignoreCase = true) ||
        snippet.contains(query, ignoreCase = true)
