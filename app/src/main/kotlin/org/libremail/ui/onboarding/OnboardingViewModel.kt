// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.onboarding

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Session state for one run of the onboarding flow. Scoped to the onboarding nav graph's back-stack
 * entry, so it is created when onboarding starts and cleared when the graph is popped.
 *
 * Its only job is to remember the **first** account added during this session: when the user
 * finishes ("No, don't add another"), onboarding opens that account's inbox (see #30).
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor() : ViewModel() {

    /** The id of the first account added this session, or null if none has been added yet. */
    var firstAddedAccountId: String? = null
        private set

    /** Records a freshly added account. Only the first one sticks — later adds don't overwrite it. */
    fun onAccountAdded(accountId: String) {
        if (firstAddedAccountId == null) {
            firstAddedAccountId = accountId
        }
    }
}
