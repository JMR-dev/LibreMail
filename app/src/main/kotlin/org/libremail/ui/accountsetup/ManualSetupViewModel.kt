// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.accountsetup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.libremail.domain.model.Account
import org.libremail.domain.model.AuthType
import org.libremail.domain.model.MailSecurity
import org.libremail.domain.model.ServerConfig
import org.libremail.domain.repository.AccountRepository
import javax.inject.Inject

data class ManualSetupForm(
    val email: String = "",
    val password: String = "",
    val imapHost: String = "",
    val imapPort: String = "993",
    val imapSecurity: MailSecurity = MailSecurity.SSL_TLS,
    val smtpHost: String = "",
    val smtpPort: String = "465",
    val smtpSecurity: MailSecurity = MailSecurity.SSL_TLS,
    val advancedExpanded: Boolean = false,
    val status: SetupStatus = SetupStatus.IDLE,
    val error: String? = null,
    /** Set alongside [SetupStatus.DONE]: the id of the account that was just added. */
    val addedAccountId: String? = null,
) {
    val isValid: Boolean
        get() = email.isNotBlank() && password.isNotBlank() && imapHost.isNotBlank() && smtpHost.isNotBlank()
}

@HiltViewModel
class ManualSetupViewModel @Inject constructor(private val accountRepository: AccountRepository) : ViewModel() {

    private companion object {
        const val MAX_PORT_DIGITS = 5
    }

    private val _form = MutableStateFlow(ManualSetupForm())
    val form: StateFlow<ManualSetupForm> = _form.asStateFlow()

    fun onEmail(value: String) = _form.update { it.copy(email = value) }
    fun onPassword(value: String) = _form.update { it.copy(password = value) }
    fun onImapHost(value: String) = _form.update { it.copy(imapHost = value) }
    fun onImapPort(value: String) =
        _form.update { it.copy(imapPort = value.filter(Char::isDigit).take(MAX_PORT_DIGITS)) }
    fun onImapSecurity(security: MailSecurity) = _form.update { it.copy(imapSecurity = security) }
    fun onSmtpHost(value: String) = _form.update { it.copy(smtpHost = value) }
    fun onSmtpPort(value: String) =
        _form.update { it.copy(smtpPort = value.filter(Char::isDigit).take(MAX_PORT_DIGITS)) }
    fun onSmtpSecurity(security: MailSecurity) = _form.update { it.copy(smtpSecurity = security) }
    fun toggleAdvanced() = _form.update { it.copy(advancedExpanded = !it.advancedExpanded) }
    fun consumeError() = _form.update { it.copy(error = null) }

    fun testAndSave() {
        val f = _form.value
        if (!f.isValid) {
            _form.update { it.copy(error = "Enter email, password, and both servers") }
            return
        }
        val account = Account(
            id = "imap:${f.email.trim()}",
            email = f.email.trim(),
            displayName = f.email.trim(),
            authType = AuthType.PASSWORD_IMAP,
            imap = ServerConfig(f.imapHost.trim(), f.imapPort.toIntOrNull() ?: 993, f.imapSecurity),
            smtp = ServerConfig(f.smtpHost.trim(), f.smtpPort.toIntOrNull() ?: 465, f.smtpSecurity),
        )
        viewModelScope.launch {
            _form.update { it.copy(status = SetupStatus.CONNECTING, error = null) }
            accountRepository.addImapAccount(account, f.password).fold(
                onSuccess = { _form.update { it.copy(status = SetupStatus.DONE, addedAccountId = account.id) } },
                onFailure = { e ->
                    _form.update {
                        it.copy(
                            status = SetupStatus.IDLE,
                            error =
                            e.message ?: "Could not connect to the server",
                        )
                    }
                },
            )
        }
    }
}
