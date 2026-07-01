// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.accountsetup

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.libremail.domain.model.MailProvider
import org.libremail.domain.repository.AccountRepository
import org.libremail.ui.navigation.Routes
import javax.inject.Inject

data class AppPasswordForm(
    val email: String = "",
    val appPassword: String = "",
    val advancedExpanded: Boolean = false,
    val status: SetupStatus = SetupStatus.IDLE,
    val error: String? = null,
    /** Set alongside [SetupStatus.DONE]: the id of the account that was just added. */
    val addedAccountId: String? = null,
) {
    val isValid: Boolean get() = email.isNotBlank() && appPassword.isNotBlank()
}

/**
 * Backs the guided app-password setup screen (#29) for the preset vendors (Gmail/Yahoo/iCloud).
 *
 * The provider is passed as a nav argument and resolved from the [MailProvider] registry, which
 * supplies the servers. The user only supplies an email + app password; this builds a
 * `PASSWORD_IMAP` [org.libremail.domain.model.Account] from the preset and reuses
 * [AccountRepository.addImapAccount] (live connection test + persist), exactly like manual setup.
 */
@HiltViewModel
class AppPasswordViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val accountRepository: AccountRepository,
) : ViewModel() {

    /** The provider preset selected in the picker; null only if an unknown key was routed here. */
    val provider: MailProvider? =
        savedStateHandle.get<String>(Routes.APP_PASSWORD_ARG_PROVIDER)?.let(MailProvider::fromKey)

    private val _form = MutableStateFlow(AppPasswordForm())
    val form: StateFlow<AppPasswordForm> = _form.asStateFlow()

    fun onEmail(value: String) = _form.update { it.copy(email = value) }
    fun onAppPassword(value: String) = _form.update { it.copy(appPassword = value) }
    fun toggleAdvanced() = _form.update { it.copy(advancedExpanded = !it.advancedExpanded) }
    fun consumeError() = _form.update { it.copy(error = null) }

    fun testAndSave() {
        val provider = provider
        if (provider == null) {
            _form.update { it.copy(error = "Unknown email provider") }
            return
        }
        val f = _form.value
        if (!f.isValid) {
            _form.update { it.copy(error = "Enter your email address and app password") }
            return
        }
        val account = provider.createAccount(f.email)
        viewModelScope.launch {
            _form.update { it.copy(status = SetupStatus.CONNECTING, error = null) }
            accountRepository.addImapAccount(account, f.appPassword).fold(
                onSuccess = {
                    _form.update { it.copy(status = SetupStatus.DONE, addedAccountId = account.id) }
                },
                onFailure = { e ->
                    _form.update {
                        it.copy(
                            status = SetupStatus.IDLE,
                            error = e.message ?: "Could not connect to the server",
                        )
                    }
                },
            )
        }
    }
}
