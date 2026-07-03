// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import org.libremail.data.settings.SettingsRepository
import org.libremail.domain.repository.AccountRepository
import org.libremail.ui.navigation.Routes
import javax.inject.Inject

/**
 * Decides the app's start destination from the stored account count: no accounts → the onboarding
 * welcome flow; otherwise the mailbox.
 *
 * [startDestination] is `null` until the first account snapshot loads — the UI holds (renders
 * nothing) during that window so a cold start never flashes the wrong screen. Only the *first*
 * determination is used ([take]), so adding the first account mid-onboarding does not later flip the
 * start destination and tear down the in-progress flow.
 */
@HiltViewModel
class AppViewModel @Inject constructor(accountRepository: AccountRepository, settingsRepository: SettingsRepository) :
    ViewModel() {

    val startDestination: StateFlow<String?> = accountRepository.observeAccounts()
        .map { accounts -> if (accounts.isEmpty()) Routes.ONBOARDING else Routes.MAILBOX }
        .take(1)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Whether the user has already agreed to the GPL-3.0 license (#172), resolved once at launch
     * exactly like [startDestination] (same "hold until known, then never re-decide" contract).
     * [LibreMailApp][org.libremail.ui.LibreMailApp] holds off building its `NavHost` until this is
     * known too, because the onboarding graph — and therefore its choice of start destination between
     * `Routes.ONBOARDING_LICENSE` and `Routes.ONBOARDING_WELCOME` — is registered as part of that same
     * `NavHost` regardless of whether [startDestination] itself resolves to onboarding or the mailbox.
     */
    val licenseAccepted: StateFlow<Boolean?> = settingsRepository.settings
        .map { it.licenseAccepted }
        .take(1)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}
