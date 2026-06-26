// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SettingsUiState(
    val dynamicColor: Boolean = true,
    val advancedExpanded: Boolean = false,
    val pushIdle: Boolean = true,
    val allowStartTls: Boolean = false,
    val loadRemoteImages: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor() : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    fun setDynamicColor(value: Boolean) = _state.update { it.copy(dynamicColor = value) }
    fun toggleAdvanced() = _state.update { it.copy(advancedExpanded = !it.advancedExpanded) }
    fun setPushIdle(value: Boolean) = _state.update { it.copy(pushIdle = value) }
    fun setAllowStartTls(value: Boolean) = _state.update { it.copy(allowStartTls = value) }
    fun setLoadRemoteImages(value: Boolean) = _state.update { it.copy(loadRemoteImages = value) }
}
