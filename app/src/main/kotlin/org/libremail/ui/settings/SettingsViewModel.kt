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
import org.libremail.data.settings.AppSettings
import org.libremail.data.settings.SettingsRepository
import org.libremail.domain.model.Account
import org.libremail.domain.repository.AccountRepository

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val accounts: StateFlow<List<Account>> = accountRepository.observeAccounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _advancedExpanded = MutableStateFlow(false)
    val advancedExpanded: StateFlow<Boolean> = _advancedExpanded.asStateFlow()

    fun toggleAdvanced() = _advancedExpanded.update { !it }

    fun removeAccount(id: String) = viewModelScope.launch { accountRepository.deleteAccount(id) }.let {}

    fun setDynamicColor(value: Boolean) = update { settingsRepository.setDynamicColor(value) }
    fun setNewMailNotifications(value: Boolean) = update { settingsRepository.setNewMailNotifications(value) }
    fun setPushIdle(value: Boolean) = update { settingsRepository.setPushIdle(value) }
    fun setAllowStartTls(value: Boolean) = update { settingsRepository.setAllowStartTls(value) }
    fun setLoadRemoteImages(value: Boolean) = update { settingsRepository.setLoadRemoteImages(value) }
    fun setEncryptCache(value: Boolean) = update { settingsRepository.setEncryptCache(value) }

    private inline fun update(crossinline action: suspend () -> Unit) {
        viewModelScope.launch { action() }
    }
}
