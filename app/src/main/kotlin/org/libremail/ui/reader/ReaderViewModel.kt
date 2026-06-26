// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import org.libremail.domain.model.Message
import org.libremail.domain.repository.MailRepository
import org.libremail.ui.navigation.Routes

@HiltViewModel
class ReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MailRepository,
) : ViewModel() {
    private val messageId: String = checkNotNull(savedStateHandle[Routes.READER_ARG_ID])

    val message: StateFlow<Message?> = flow {
        emit(repository.getMessage(messageId))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
