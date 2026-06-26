// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.mailbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import org.libremail.domain.model.Message
import org.libremail.domain.repository.MailRepository

@HiltViewModel
class MailboxViewModel @Inject constructor(
    repository: MailRepository,
) : ViewModel() {
    val messages: StateFlow<List<Message>> =
        repository.observeMessages()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
