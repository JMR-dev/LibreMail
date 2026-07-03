// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import org.libremail.data.settings.SettingsRepository
import org.libremail.domain.repository.AccountRepository
import org.libremail.ui.navigation.Routes
import javax.inject.Inject

/**
 * Decides the app's start destination from two launch-time facts: whether any account is stored and
 * whether the GPL-3.0 license has been accepted (#172). The license gate comes first — an un-accepted
 * license routes to onboarding (which begins at the license screen) even when accounts already exist,
 * so a user upgrading from a pre-#172 install can't skip the license by virtue of already having mail.
 * Only a user who has accepted AND has at least one account lands straight on the mailbox.
 *
 * Each exposed flow is `null` until the first snapshot loads — the UI holds (renders nothing) during
 * that window so a cold start never flashes the wrong screen. Only the *first* determination is used
 * ([take]), so adding the first account (or accepting the license) mid-onboarding does not later flip
 * the start destination and tear down the in-progress flow.
 */
@HiltViewModel
class AppViewModel @Inject constructor(accountRepository: AccountRepository, settingsRepository: SettingsRepository) :
    ViewModel() {

    val startDestination: StateFlow<String?> = combine(
        accountRepository.observeAccounts(),
        settingsRepository.settings,
    ) { accounts, settings ->
        if (settings.licenseAccepted && accounts.isNotEmpty()) Routes.MAILBOX else Routes.ONBOARDING
    }
        .take(1)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Whether the user has already agreed to the GPL-3.0 license (#172), resolved once at launch.
     * [LibreMailApp][org.libremail.ui.LibreMailApp] uses it to pick the onboarding graph's own start
     * destination between `Routes.ONBOARDING_LICENSE` and `Routes.ONBOARDING_WELCOME`, and holds off
     * building its `NavHost` until this is known — the onboarding graph is registered as part of that
     * `NavHost` regardless of whether [startDestination] resolves to onboarding or the mailbox.
     */
    val licenseAccepted: StateFlow<Boolean?> = settingsRepository.settings
        .map { it.licenseAccepted }
        .take(1)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Whether at least one account already exists, resolved once at launch. Lets
     * [LibreMailApp][org.libremail.ui.LibreMailApp] route correctly *after* an upgrade user accepts the
     * license: someone who already had accounts goes straight to the mailbox rather than the "add your
     * first account" welcome screen (which would be a dead-end for them), while a fresh install with no
     * accounts continues into that welcome flow as before.
     */
    val hasAccounts: StateFlow<Boolean?> = accountRepository.observeAccounts()
        .map { it.isNotEmpty() }
        .take(1)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}
