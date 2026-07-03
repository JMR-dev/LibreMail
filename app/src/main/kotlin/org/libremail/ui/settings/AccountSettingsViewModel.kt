// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.libremail.data.settings.AccountSettingsRepository
import org.libremail.data.settings.SettingsRepository
import org.libremail.data.settings.SignatureRepository
import org.libremail.data.sync.SyncScheduler
import org.libremail.domain.model.Account
import org.libremail.domain.model.AccountSettings
import org.libremail.domain.repository.AccountRepository
import org.libremail.notifications.MailNotifier
import org.libremail.ui.navigation.Routes
import javax.inject.Inject

@HiltViewModel
class AccountSettingsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val accountRepository: AccountRepository,
    private val accountSettingsRepository: AccountSettingsRepository,
    signatureRepository: SignatureRepository,
    private val syncScheduler: SyncScheduler,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val accountId: String =
        checkNotNull(savedStateHandle.get<String>(Routes.ACCOUNT_SETTINGS_ARG_ID))

    val account: StateFlow<Account?> = accountRepository.observeAccounts()
        .map { list -> list.firstOrNull { it.id == accountId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val settings: StateFlow<AccountSettings> = accountSettingsRepository.observe(accountId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountSettings(accountId))

    /** Whether this account is the user-chosen default (issue #163) — see [SettingsRepository]. */
    val isDefaultAccount: StateFlow<Boolean> = settingsRepository.settings
        .map { it.defaultAccountId == accountId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val signaturesFlow = signatureRepository.observeForAccount(accountId)

    val signatureCount: StateFlow<Int> = signaturesFlow
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** The name of the account's default signature (for the settings summary line), or "". */
    val defaultSignatureName: StateFlow<String> = signaturesFlow
        .map { list -> list.firstOrNull { it.isDefault }?.name ?: list.firstOrNull()?.name ?: "" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /** This account's notification channel id, for deep-linking into Android's system settings. */
    val notificationChannelId: String = MailNotifier.channelId(accountId)

    fun setSignatureEnabled(value: Boolean) {
        viewModelScope.launch { accountSettingsRepository.setSignatureEnabled(accountId, value) }
    }

    fun setNotificationsEnabled(value: Boolean) {
        viewModelScope.launch { accountSettingsRepository.setNotificationsEnabled(accountId, value) }
    }

    /** Per-account device-only retention overrides (null = inherit the global default). Prunes promptly. */
    fun setRetentionCount(value: Int?) {
        viewModelScope.launch {
            accountSettingsRepository.setRetentionCount(accountId, value)
            syncScheduler.pruneNow()
            accountRepository.resetBackfillProgress(accountId)
        }
    }

    fun setRetentionMonths(value: Int?) {
        viewModelScope.launch {
            accountSettingsRepository.setRetentionMonths(accountId, value)
            syncScheduler.pruneNow()
            accountRepository.resetBackfillProgress(accountId)
        }
    }

    /**
     * Makes this account the default (used by compose's from-account fallback, #163), or clears the
     * default when turned off. Only one account is default at a time — persisting this account's id
     * implicitly un-defaults whichever one held it before.
     */
    fun setDefaultAccount(isDefault: Boolean) {
        viewModelScope.launch {
            if (isDefault) {
                settingsRepository.setDefaultAccountId(accountId)
            } else {
                settingsRepository.clearDefaultAccountId(accountId)
            }
        }
    }

    fun removeAccount(onRemoved: () -> Unit) {
        viewModelScope.launch {
            accountRepository.deleteAccount(accountId)
            // Don't strand the preference on a deleted account; only clears it if this account was
            // actually the default (see SettingsRepository.clearDefaultAccountId).
            settingsRepository.clearDefaultAccountId(accountId)
            onRemoved()
        }
    }
}
