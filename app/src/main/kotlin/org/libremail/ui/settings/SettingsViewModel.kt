// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.libremail.domain.model.Account
import org.libremail.domain.repository.AccountRepository

data class SettingsUiState(
    val dynamicColor: Boolean = true,
    val advancedExpanded: Boolean = false,
    val pushIdle: Boolean = true,
    val allowStartTls: Boolean = false,
    val loadRemoteImages: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    val accounts: StateFlow<List<Account>> = accountRepository.observeAccounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun removeAccount(id: String) {
        viewModelScope.launch { accountRepository.deleteAccount(id) }
    }

    fun setDynamicColor(value: Boolean) = _state.update { it.copy(dynamicColor = value) }
    fun toggleAdvanced() = _state.update { it.copy(advancedExpanded = !it.advancedExpanded) }
    fun setPushIdle(value: Boolean) = _state.update { it.copy(pushIdle = value) }
    fun setAllowStartTls(value: Boolean) = _state.update { it.copy(allowStartTls = value) }
    fun setLoadRemoteImages(value: Boolean) = _state.update { it.copy(loadRemoteImages = value) }
}
