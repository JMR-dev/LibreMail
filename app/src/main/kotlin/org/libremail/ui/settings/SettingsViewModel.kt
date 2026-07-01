// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.libremail.R
import org.libremail.data.security.AppLockManager
import org.libremail.data.security.DatabaseKeyStore
import org.libremail.data.settings.AppSettings
import org.libremail.data.settings.FetchPolicy
import org.libremail.data.settings.SettingsRepository
import org.libremail.domain.model.Account
import org.libremail.domain.repository.AccountRepository
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val settingsRepository: SettingsRepository,
    private val appLockManager: AppLockManager,
    private val databaseKeyStore: DatabaseKeyStore,
) : ViewModel() {

    val accounts: StateFlow<List<Account>> = accountRepository.observeAccounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _advancedExpanded = MutableStateFlow(false)
    val advancedExpanded: StateFlow<Boolean> = _advancedExpanded.asStateFlow()

    /** Non-null when a toggle was rejected; a string resource id the screen can surface, then clear. */
    private val _appLockMessage = MutableStateFlow<Int?>(null)
    val appLockMessage: StateFlow<Int?> = _appLockMessage.asStateFlow()

    fun toggleAdvanced() = _advancedExpanded.update { !it }

    fun setDynamicColor(value: Boolean) = update { settingsRepository.setDynamicColor(value) }
    fun setNewMailNotifications(value: Boolean) = update { settingsRepository.setNewMailNotifications(value) }
    fun setPushIdle(value: Boolean) = update { settingsRepository.setPushIdle(value) }
    fun setAllowStartTls(value: Boolean) = update { settingsRepository.setAllowStartTls(value) }
    fun setLoadRemoteImages(value: Boolean) = update { settingsRepository.setLoadRemoteImages(value) }
    fun setEncryptCache(value: Boolean) = update { settingsRepository.setEncryptCache(value) }
    fun setFetchPolicy(value: FetchPolicy) = update { settingsRepository.setFetchPolicy(value) }

    /**
     * Toggle app-lock. Enabling requires a secure device lock (otherwise there is nothing to
     * authenticate against) — rejected with a message if absent. Disabling reseals the cache
     * passphrase with the non-auth master key so it stays readable without authentication; the user
     * is already authenticated for this session (they passed the gate to reach settings).
     */
    fun setAppLock(value: Boolean) = update {
        if (value) {
            if (!appLockManager.isDeviceSecure()) {
                _appLockMessage.value = R.string.app_lock_needs_device_lock
                return@update
            }
            settingsRepository.setAppLock(true)
        } else {
            if (settingsRepository.settings.first().encryptCache) {
                // Move the passphrase back under the master key BEFORE dropping the gate, or the next
                // launch derives a fresh (wrong) key and can't open the encrypted cache. If it fails,
                // keep app-lock on rather than risk that mismatch.
                if (runCatching { databaseKeyStore.sealWithMaster() }.isFailure) {
                    _appLockMessage.value = R.string.app_lock_disable_failed
                    return@update
                }
            }
            settingsRepository.setAppLock(false)
        }
    }

    fun clearAppLockMessage() {
        _appLockMessage.value = null
    }

    private inline fun update(crossinline action: suspend () -> Unit) {
        viewModelScope.launch { action() }
    }
}
