// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.libremail.domain.model.Message
import org.libremail.domain.repository.MailRepository
import org.libremail.ui.navigation.Routes

data class ReaderUiState(
    val loading: Boolean = true,
    val message: Message? = null,
    val loadRemoteImages: Boolean = false,
    val deleted: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MailRepository,
) : ViewModel() {

    private val messageId: String = checkNotNull(savedStateHandle[Routes.READER_ARG_ID])

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.openMessage(messageId).fold(
                onSuccess = { message -> _state.update { it.copy(loading = false, message = message) } },
                onFailure = { e -> _state.update { it.copy(loading = false, error = e.message ?: "Could not load message") } },
            )
        }
    }

    fun toggleStar() {
        val message = _state.value.message ?: return
        val starred = !message.isStarred
        _state.update { it.copy(message = message.copy(isStarred = starred)) }
        viewModelScope.launch { repository.setStarred(messageId, starred) }
    }

    fun loadRemoteImages() = _state.update { it.copy(loadRemoteImages = true) }

    fun delete() {
        viewModelScope.launch {
            repository.deleteMessage(messageId)
            _state.update { it.copy(deleted = true) }
        }
    }
}
