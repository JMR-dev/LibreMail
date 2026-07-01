// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.settings

import androidx.lifecycle.SavedStateHandle
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.libremail.data.settings.AccountSettingsRepository
import org.libremail.domain.model.Account
import org.libremail.domain.model.AccountSettings
import org.libremail.domain.repository.AccountRepository
import org.libremail.notifications.MailNotifier
import org.libremail.ui.navigation.Routes

@HiltViewModel
class AccountSettingsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val accountRepository: AccountRepository,
    private val accountSettingsRepository: AccountSettingsRepository,
) : ViewModel() {

    private val accountId: String =
        checkNotNull(savedStateHandle.get<String>(Routes.ACCOUNT_SETTINGS_ARG_ID))

    val account: StateFlow<Account?> = accountRepository.observeAccounts()
        .map { list -> list.firstOrNull { it.id == accountId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val settings: StateFlow<AccountSettings> = accountSettingsRepository.observe(accountId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountSettings(accountId))

    // The signature text is edited locally (seeded once from persistence) so the field stays
    // responsive — a fully DB-driven value would lag each keystroke and jump the cursor.
    private val _signature = MutableStateFlow<String?>(null)
    val signature: StateFlow<String?> = _signature.asStateFlow()

    /** This account's notification channel id, for deep-linking into Android's system settings. */
    val notificationChannelId: String = MailNotifier.channelId(accountId)

    init {
        viewModelScope.launch {
            val loaded = accountSettingsRepository.get(accountId).signature
            _signature.update { it ?: loaded } // don't clobber any text typed before the load returned
        }
    }

    fun onSignatureChange(value: String) {
        _signature.value = value
        viewModelScope.launch { accountSettingsRepository.setSignature(accountId, value) }
    }

    fun setSignatureEnabled(value: Boolean) {
        viewModelScope.launch { accountSettingsRepository.setSignatureEnabled(accountId, value) }
    }

    fun setNotificationsEnabled(value: Boolean) {
        viewModelScope.launch { accountSettingsRepository.setNotificationsEnabled(accountId, value) }
    }

    fun removeAccount(onRemoved: () -> Unit) {
        viewModelScope.launch {
            accountRepository.deleteAccount(accountId)
            onRemoved()
        }
    }
}
