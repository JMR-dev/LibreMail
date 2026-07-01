// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.onboarding

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.libremail.data.settings.SettingsRepository
import org.libremail.push.BatteryOptimizationManager
import org.libremail.push.BatteryPromptDecision
import javax.inject.Inject

/**
 * Session state for one run of the onboarding flow. Scoped to the onboarding nav graph's back-stack
 * entry, so it is created when onboarding starts and cleared when the graph is popped.
 *
 * It remembers the **first** account added this session (so finishing opens that account's inbox, see
 * #30) and decides whether to show the "unrestricted battery" opt-in step before finishing (see #49).
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val batteryOptimizationManager: BatteryOptimizationManager,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    /** The id of the first account added this session, or null if none has been added yet. */
    var firstAddedAccountId: String? = null
        private set

    private val _batteryPromptNeeded = MutableStateFlow<Boolean?>(null)

    /**
     * Whether onboarding should show the battery opt-in step before finishing. `null` until decided;
     * the finish path treats `null` as "skip", so a slow read can never block the end of onboarding.
     * Decided once at graph start — neither input can change until the user reaches the step itself.
     */
    val batteryPromptNeeded: StateFlow<Boolean?> = _batteryPromptNeeded.asStateFlow()

    private val _batteryUnrestricted = MutableStateFlow(false)

    /** Live "Unrestricted" status, re-read when the opt-in step resumes (e.g. back from Settings). */
    val batteryUnrestricted: StateFlow<Boolean> = _batteryUnrestricted.asStateFlow()

    init {
        viewModelScope.launch {
            val unrestricted = batteryOptimizationManager.isIgnoringBatteryOptimizations()
            _batteryUnrestricted.value = unrestricted
            _batteryPromptNeeded.value = BatteryPromptDecision.shouldPrompt(
                supported = batteryOptimizationManager.isSupported,
                alreadyUnrestricted = unrestricted,
                alreadyHandled = settingsRepository.isBatteryPromptHandled(),
            )
        }
    }

    /** Records a freshly added account. Only the first one sticks — later adds don't overwrite it. */
    fun onAccountAdded(accountId: String) {
        if (firstAddedAccountId == null) {
            firstAddedAccountId = accountId
        }
    }

    /** Intent to the system screen where the user flips this app to "Unrestricted". */
    fun batterySettingsIntent(): Intent = batteryOptimizationManager.settingsIntent()

    /** Re-read the live battery status; call when the opt-in step resumes. */
    fun refreshBatteryStatus() {
        viewModelScope.launch {
            _batteryUnrestricted.value = batteryOptimizationManager.isIgnoringBatteryOptimizations()
        }
    }

    /** Record that the user has seen/acted on the battery opt-in so onboarding won't ask again. */
    fun markBatteryPromptHandled() {
        viewModelScope.launch { settingsRepository.setBatteryPromptHandled(true) }
    }
}
