// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.compose

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.libremail.contacts.ContactSuggestion
import org.libremail.contacts.ContactsRepository
import org.libremail.domain.model.Account
import org.libremail.domain.model.Draft
import org.libremail.domain.model.OutgoingMessage
import org.libremail.domain.repository.AccountRepository
import org.libremail.domain.repository.MailRepository
import org.libremail.ui.navigation.Routes

data class ComposeUiState(
    val to: String = "",
    val cc: String = "",
    val subject: String = "",
    val body: String = "",
    val fromAccountId: String? = null,
    val suggestions: List<ContactSuggestion> = emptyList(),
    val contactsAllowed: Boolean = false,
    val sending: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class ComposeViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val mailRepository: MailRepository,
    accountRepository: AccountRepository,
    private val contactsRepository: ContactsRepository,
) : ViewModel() {

    private val draftId: String? =
        savedStateHandle.get<String>(Routes.COMPOSE_ARG_DRAFT)?.takeIf { it.isNotBlank() }

    private val _state = MutableStateFlow(
        ComposeUiState(
            to = savedStateHandle.get<String>(Routes.COMPOSE_ARG_TO).orEmpty(),
            subject = savedStateHandle.get<String>(Routes.COMPOSE_ARG_SUBJECT).orEmpty(),
            fromAccountId = savedStateHandle.get<String>(Routes.COMPOSE_ARG_FROM)?.takeIf { it.isNotBlank() },
        ),
    )
    val state: StateFlow<ComposeUiState> = _state.asStateFlow()

    val accounts: StateFlow<List<Account>> = accountRepository.observeAccounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Emitted when the screen should close — after the draft is saved/deleted, or after sending. */
    private val _finished = Channel<Unit>(Channel.BUFFERED)
    val finished = _finished.receiveAsFlow()

    private var searchJob: Job? = null

    init {
        if (draftId != null) {
            viewModelScope.launch {
                mailRepository.getDraft(draftId)?.let { draft ->
                    _state.update {
                        it.copy(
                            to = draft.to,
                            cc = draft.cc,
                            subject = draft.subject,
                            body = draft.body,
                            fromAccountId = draft.accountId ?: it.fromAccountId,
                        )
                    }
                }
            }
        }
    }

    fun onToChange(value: String) {
        _state.update { it.copy(to = value) }
        searchContacts(value)
    }

    fun onCcChange(value: String) = _state.update { it.copy(cc = value) }
    fun onSubjectChange(value: String) = _state.update { it.copy(subject = value) }
    fun onBodyChange(value: String) = _state.update { it.copy(body = value) }
    fun selectFrom(accountId: String) = _state.update { it.copy(fromAccountId = accountId) }
    fun consumeError() = _state.update { it.copy(error = null) }
    fun onContactsPermission(granted: Boolean) = _state.update { it.copy(contactsAllowed = granted) }

    fun pickSuggestion(suggestion: ContactSuggestion) {
        val current = _state.value.to
        val prefix = if (current.contains(',')) current.substringBeforeLast(',') + ", " else ""
        _state.update { it.copy(to = prefix + suggestion.email, suggestions = emptyList()) }
    }

    private fun searchContacts(value: String) {
        searchJob?.cancel()
        if (!_state.value.contactsAllowed) return
        val token = value.substringAfterLast(',').trim()
        if (token.length < 2) {
            _state.update { it.copy(suggestions = emptyList()) }
            return
        }
        searchJob = viewModelScope.launch {
            val results = contactsRepository.search(token)
            _state.update { it.copy(suggestions = results) }
        }
    }

    /** Leaving the screen: keep a draft if there's anything worth keeping, then close. */
    fun onExit() {
        viewModelScope.launch {
            saveOrDeleteDraft()
            _finished.send(Unit)
        }
    }

    private suspend fun saveOrDeleteDraft() {
        val s = _state.value
        val hasContent = s.to.isNotBlank() || s.cc.isNotBlank() || s.subject.isNotBlank() || s.body.isNotBlank()
        when {
            hasContent -> mailRepository.saveDraft(
                Draft(
                    id = draftId ?: UUID.randomUUID().toString(),
                    accountId = s.fromAccountId,
                    to = s.to,
                    cc = s.cc,
                    subject = s.subject,
                    body = s.body,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            draftId != null -> mailRepository.deleteDraft(draftId) // an existing draft was emptied out
        }
    }

    fun send() {
        val s = _state.value
        val account = accounts.value.firstOrNull { it.id == s.fromAccountId } ?: accounts.value.firstOrNull()
        when {
            account == null -> _state.update { it.copy(error = "Add an account first") }
            s.to.isBlank() -> _state.update { it.copy(error = "Add a recipient") }
            else -> viewModelScope.launch {
                _state.update { it.copy(sending = true, error = null) }
                mailRepository.sendMessage(
                    OutgoingMessage(account.id, s.to, s.cc, s.subject, s.body),
                ).fold(
                    onSuccess = {
                        draftId?.let { mailRepository.deleteDraft(it) }
                        _state.update { it.copy(sending = false) }
                        _finished.send(Unit)
                    },
                    onFailure = { e -> _state.update { it.copy(sending = false, error = e.message ?: "Could not send") } },
                )
            }
        }
    }
}
