// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.accountsetup

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.libremail.auth.OutlookAuthManager
import org.libremail.domain.model.Account
import org.libremail.domain.repository.AccountRepository
import org.libremail.reporting.AppLog
import org.libremail.reporting.accountLogRef
import javax.inject.Inject

/** Stage of an account-setup attempt, shared by the Outlook and manual flows. */
enum class SetupStatus { IDLE, CONNECTING, DONE }

data class AccountSetupUiState(
    val status: SetupStatus = SetupStatus.IDLE,
    val error: String? = null,
    /** Set alongside [SetupStatus.DONE]: the id of the account that was just added. */
    val addedAccountId: String? = null,
    /**
     * Set instead of [error] when the failure was specifically an "IMAP is disabled" rejection (#390):
     * the screen shows the actionable [ImapDisabledDialog] rather than the generic error snackbar.
     */
    val imapDisabledPrompt: ImapDisabledPrompt? = null,
)

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
            // A null result Intent is a normal user cancel: backing out of the Microsoft sign-in tab
            // returns RESULT_CANCELED with no data. That is expected, not a failure, so it is a no-op —
            // surfacing an error snackbar for a deliberate cancel is just noise (#308).
            AppLog.d(TAG, "Outlook sign-in cancelled by the user; no-op")
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(status = SetupStatus.CONNECTING, error = null) }
            // Captured so the failure branch can tell a token/consent failure (account still null) from
            // a token-OK-but-IMAP-AUTHENTICATE-rejected one (account set), which is the #390 signal.
            var account: Account? = null
            runCatching {
                val oauth = outlookAuthManager.exchangeToken(data)
                val acct = Account.outlook(oauth.email)
                account = acct
                accountRepository.addOutlookAccount(oauth.email, oauth.accessToken, oauth.authStateJson).getOrThrow()
                acct.id
            }.fold(
                onSuccess = { accountId ->
                    // No email: the account id embeds it (see accountLogRef) and must never be logged.
                    AppLog.i(TAG, "Outlook account added")
                    _state.update { it.copy(status = SetupStatus.DONE, addedAccountId = accountId) }
                },
                onFailure = { e -> onOutlookFailure(e, account) },
            )
        }
    }

    /**
     * Routes a failed Outlook add. When the OAuth token was obtained ([account] set) but the IMAP
     * `AUTHENTICATE` step was rejected because IMAP is disabled, surfaces the actionable "turn on IMAP"
     * prompt (#390); otherwise (token/consent failure, or any other error) keeps the generic message.
     */
    private fun onOutlookFailure(e: Throwable, account: Account?) {
        // AppLog.d's Logcat mirror is stripped from release builds by the -assumenosideeffects
        // Log.d ProGuard rule (keeps any account address / token detail out of shipped logcat), but
        // that rule only elides the `Log.d(...)` call inside AppLog.d — the buffer.record(...) line
        // right after it is untouched, so this breadcrumb still reaches a submitted report. The
        // throwable's message may carry the account email/token; AppLog's StackTraceScrubber redacts
        // it before it is recorded.
        AppLog.d(TAG, "Outlook sign-in failed after redirect", e)
        val prompt = account?.let { imapDisabledPromptFor(e, it, usedOAuth = true) }
        if (prompt != null && account != null) {
            AppLog.i(TAG, "IMAP disabled on Outlook sign-in (${accountLogRef(account.id)}); prompting to enable IMAP")
            _state.update { it.copy(status = SetupStatus.IDLE, imapDisabledPrompt = prompt, error = null) }
        } else {
            _state.update { it.copy(status = SetupStatus.IDLE, error = e.message ?: "Microsoft sign-in failed") }
        }
    }

    fun consumeError() = _state.update { it.copy(error = null) }

    /** Clears the "IMAP is disabled" prompt after the user acknowledges it (issue #390). */
    fun dismissImapDisabledPrompt() = _state.update { it.copy(imapDisabledPrompt = null) }

    private companion object {
        const val TAG = "AccountSetupVM"
    }
}
