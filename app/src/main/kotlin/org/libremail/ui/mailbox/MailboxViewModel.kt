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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.libremail.data.sync.MailSyncer
import org.libremail.domain.model.Message
import org.libremail.domain.repository.AccountRepository
import org.libremail.domain.repository.MailRepository

@HiltViewModel
class MailboxViewModel @Inject constructor(
    mailRepository: MailRepository,
    accountRepository: AccountRepository,
    private val mailSyncer: MailSyncer,
) : ViewModel() {

    val messages: StateFlow<List<Message>> =
        mailRepository.observeMessages()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val hasAccounts: StateFlow<Boolean> =
        accountRepository.observeAccounts()
            .map { it.isNotEmpty() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

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
