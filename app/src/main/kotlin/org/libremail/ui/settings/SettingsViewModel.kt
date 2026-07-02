// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.settings

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.libremail.R
import org.libremail.data.security.AppLockManager
import org.libremail.data.security.DatabaseKeyStore
import org.libremail.data.settings.AppSettings
import org.libremail.data.settings.FetchPolicy
import org.libremail.data.settings.SettingsRepository
import org.libremail.data.sync.SyncScheduler
import org.libremail.domain.model.Account
import org.libremail.domain.repository.AccountRepository
import org.libremail.push.BatteryOptimizationManager
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val settingsRepository: SettingsRepository,
    private val appLockManager: AppLockManager,
    private val databaseKeyStore: DatabaseKeyStore,
    private val batteryOptimizationManager: BatteryOptimizationManager,
    private val syncScheduler: SyncScheduler,
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

    private val _batteryUnrestricted = MutableStateFlow(batteryOptimizationManager.isIgnoringBatteryOptimizations())

    /** Whether this app is exempt from battery optimization ("Unrestricted"). */
    val batteryUnrestricted: StateFlow<Boolean> = _batteryUnrestricted.asStateFlow()

    fun toggleAdvanced() = _advancedExpanded.update { !it }

    /** Re-read the battery-optimization status; call when the screen resumes (e.g. back from Settings). */
    fun refreshBatteryStatus() {
        _batteryUnrestricted.value = batteryOptimizationManager.isIgnoringBatteryOptimizations()
    }

    /** Intent to the system screen where the user flips this app to "Unrestricted". */
    fun batterySettingsIntent(): Intent = batteryOptimizationManager.settingsIntent()

    fun setDynamicColor(value: Boolean) = update { settingsRepository.setDynamicColor(value) }
    fun setNewMailNotifications(value: Boolean) = update { settingsRepository.setNewMailNotifications(value) }
    fun setPushIdle(value: Boolean) = update { settingsRepository.setPushIdle(value) }
    fun setAllowStartTls(value: Boolean) = update { settingsRepository.setAllowStartTls(value) }
    fun setLoadRemoteImages(value: Boolean) = update { settingsRepository.setLoadRemoteImages(value) }
    fun setEncryptCache(value: Boolean) = update { settingsRepository.setEncryptCache(value) }
    fun setIncludeInBackup(value: Boolean) = update { settingsRepository.setIncludeInBackup(value) }
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
            // Reseal under the master key whenever an auth seal actually exists — gate on the seal, not
            // the encryptCache setting (a separate store that can already be off while the on-disk DB is
            // still auth-sealed). Do it BEFORE dropping the gate, or the next launch can't open the
            // cache. If it fails, keep app-lock on rather than strand the passphrase.
            if (databaseKeyStore.hasAuthSealedPassphrase()) {
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

    /** Global retention defaults; kick a prune so a newly-tightened limit takes effect promptly (#13). */
    fun setRetentionCount(value: Int) = update {
        settingsRepository.setRetentionCount(value)
        syncScheduler.pruneNow()
        accountRepository.resetBackfillProgress(null)
    }

    fun setRetentionMonths(value: Int) = update {
        settingsRepository.setRetentionMonths(value)
        syncScheduler.pruneNow()
        accountRepository.resetBackfillProgress(null)
    }

    private inline fun update(crossinline action: suspend () -> Unit) {
        viewModelScope.launch { action() }
    }
}
