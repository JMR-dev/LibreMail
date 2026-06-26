// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.compose

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ComposeUiState(
    val to: String = "",
    val subject: String = "",
    val body: String = "",
)

@HiltViewModel
class ComposeViewModel @Inject constructor() : ViewModel() {
    private val _state = MutableStateFlow(ComposeUiState())
    val state: StateFlow<ComposeUiState> = _state.asStateFlow()

    fun onToChange(value: String) = _state.update { it.copy(to = value) }
    fun onSubjectChange(value: String) = _state.update { it.copy(subject = value) }
    fun onBodyChange(value: String) = _state.update { it.copy(body = value) }
}
