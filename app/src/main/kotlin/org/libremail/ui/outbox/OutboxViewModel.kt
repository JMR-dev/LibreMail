// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.outbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.libremail.domain.model.OutboxMessage
import org.libremail.domain.repository.MailRepository
import javax.inject.Inject

@HiltViewModel
class OutboxViewModel @Inject constructor(private val repository: MailRepository) : ViewModel() {

    val messages: StateFlow<List<OutboxMessage>> = repository.observeOutbox()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun cancel(id: String) {
        viewModelScope.launch { repository.cancelOutboxMessage(id) }
    }

    fun retry() {
        viewModelScope.launch { repository.retryOutbox() }
    }
}
