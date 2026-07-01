// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.accountsetup

import android.content.ActivityNotFoundException
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.libremail.auth.OutlookAuthManager
import org.libremail.domain.repository.AccountRepository
import javax.inject.Inject

/** Stage of an account-setup attempt, shared by the Outlook and manual flows. */
enum class SetupStatus { IDLE, CONNECTING, DONE }

data class AccountSetupUiState(val status: SetupStatus = SetupStatus.IDLE, val error: String? = null)

@HiltViewModel
class AccountSetupViewModel @Inject constructor(
    private val outlookAuthManager: OutlookAuthManager,
    private val accountRepository: AccountRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AccountSetupUiState())
    val state: StateFlow<AccountSetupUiState> = _state.asStateFlow()

    val isOutlookConfigured: Boolean get() = outlookAuthManager.isConfigured

    /**
     * Builds the Microsoft sign-in intent. Wrapped in [Result] because AppAuth throws
     * (e.g. [ActivityNotFoundException] when no browser is available) while building it; the
     * screen surfaces a failure as an error instead of letting it crash the app.
     */
    fun outlookAuthIntent(): Result<Intent> = runCatching { outlookAuthManager.createAuthIntent() }

    /** Reports a failure to build or launch the sign-in intent through the error snackbar. */
    fun onOutlookLaunchFailed(error: Throwable) {
        val message = if (error is ActivityNotFoundException) {
            "No web browser is available for Microsoft sign-in"
        } else {
            error.message ?: "Couldn't start Microsoft sign-in"
        }
        _state.update { it.copy(status = SetupStatus.IDLE, error = message) }
    }

    fun onOutlookResult(data: Intent?) {
        if (data == null) {
            _state.update { it.copy(error = "Microsoft sign-in was cancelled") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(status = SetupStatus.CONNECTING, error = null) }
            runCatching {
                val oauth = outlookAuthManager.exchangeToken(data)
                accountRepository.addOutlookAccount(oauth.email, oauth.accessToken, oauth.authStateJson).getOrThrow()
            }.fold(
                onSuccess = { _state.update { it.copy(status = SetupStatus.DONE) } },
                onFailure = { e ->
                    // Stripped from release builds by the Log.d ProGuard rule (keeps any account
                    // address / token detail out of shipped logs); visible in debug for diagnosis.
                    Log.d(TAG, "Outlook sign-in failed after redirect", e)
                    _state.update {
                        it.copy(status = SetupStatus.IDLE, error = e.message ?: "Microsoft sign-in failed")
                    }
                },
            )
        }
    }

    fun consumeError() = _state.update { it.copy(error = null) }

    private companion object {
        const val TAG = "AccountSetupVM"
    }
}
