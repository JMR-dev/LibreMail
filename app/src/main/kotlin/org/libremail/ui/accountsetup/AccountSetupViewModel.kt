// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.accountsetup

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.libremail.auth.GmailAuthManager
import org.libremail.domain.repository.AccountRepository

/** Stage of an account-setup attempt, shared by the Gmail and manual flows. */
enum class SetupStatus { IDLE, CONNECTING, DONE }

data class AccountSetupUiState(
    val status: SetupStatus = SetupStatus.IDLE,
    val error: String? = null,
)

@HiltViewModel
class AccountSetupViewModel @Inject constructor(
    private val authManager: GmailAuthManager,
    private val accountRepository: AccountRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AccountSetupUiState())
    val state: StateFlow<AccountSetupUiState> = _state.asStateFlow()

    val isGmailConfigured: Boolean get() = authManager.isConfigured

    fun gmailAuthIntent(): Intent = authManager.createAuthIntent()

    fun onGmailResult(data: Intent?) {
        if (data == null) {
            _state.update { it.copy(error = "Google sign-in was cancelled") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(status = SetupStatus.CONNECTING, error = null) }
            runCatching {
                val oauth = authManager.exchangeToken(data)
                accountRepository.addGmailAccount(oauth.email, oauth.accessToken, oauth.authStateJson).getOrThrow()
            }.fold(
                onSuccess = { _state.update { it.copy(status = SetupStatus.DONE) } },
                onFailure = { e -> _state.update { it.copy(status = SetupStatus.IDLE, error = e.message ?: "Gmail sign-in failed") } },
            )
        }
    }

    fun consumeError() = _state.update { it.copy(error = null) }
}
