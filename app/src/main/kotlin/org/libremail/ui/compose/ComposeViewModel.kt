// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.compose

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.libremail.contacts.ContactSuggestion
import org.libremail.contacts.ContactsRepository
import org.libremail.data.SignatureBlock
import org.libremail.data.settings.AccountSettingsRepository
import org.libremail.data.settings.SettingsRepository
import org.libremail.data.settings.SignatureRepository
import org.libremail.domain.model.Account
import org.libremail.domain.model.Draft
import org.libremail.domain.model.OutgoingAttachment
import org.libremail.domain.model.OutgoingMessage
import org.libremail.domain.repository.AccountRepository
import org.libremail.domain.repository.MailRepository
import org.libremail.richtext.RichTextContent
import org.libremail.richtext.RichTextHtml
import org.libremail.ui.navigation.Routes
import java.util.UUID
import javax.inject.Inject

data class ComposeUiState(
    val to: String = "",
    val cc: String = "",
    val bcc: String = "",
    val subject: String = "",
    /** The plaintext form of the body (also the `text/plain` fallback when sending). */
    val body: String = "",
    /** The HTML form of the body, or null when the message carries no formatting (plaintext-only). */
    val bodyHtml: String? = null,
    val fromAccountId: String? = null,
    val attachments: List<OutgoingAttachment> = emptyList(),
    val suggestions: List<ContactSuggestion> = emptyList(),
    val contactsAllowed: Boolean = false,
    val sending: Boolean = false,
    val error: String? = null,
    /** Send was tapped while the text mentions an attachment but none is attached: ask first. */
    val showAttachmentPrompt: Boolean = false,
    /** Draw the eye to the attach button — the user answered the attachment prompt with "Yes". */
    val highlightAttach: Boolean = false,
)

@HiltViewModel
class ComposeViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val mailRepository: MailRepository,
    private val accountRepository: AccountRepository,
    private val contactsRepository: ContactsRepository,
    private val accountSettingsRepository: AccountSettingsRepository,
    private val signatureRepository: SignatureRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val draftId: String? =
        savedStateHandle.get<String>(Routes.COMPOSE_ARG_DRAFT)?.takeIf { it.isNotBlank() }

    private val _state = MutableStateFlow(
        ComposeUiState(
            to = savedStateHandle.get<String>(Routes.COMPOSE_ARG_TO).orEmpty(),
            cc = savedStateHandle.get<String>(Routes.COMPOSE_ARG_CC).orEmpty(),
            bcc = savedStateHandle.get<String>(Routes.COMPOSE_ARG_BCC).orEmpty(),
            subject = savedStateHandle.get<String>(Routes.COMPOSE_ARG_SUBJECT).orEmpty(),
            body = savedStateHandle.get<String>(Routes.COMPOSE_ARG_BODY).orEmpty(),
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

    /** Guards against double-navigation and against saving a draft for an already-sent message. */
    @Volatile private var navigated = false

    /** The signature block last appended to the body, so a From-change can swap it out cleanly. */
    private var appliedSignatureBlock = SignatureBlock.EMPTY

    /** Word-bounded "attach" and variants (attached, attachment(s), attaching, attaches). */
    private val attachmentMention = Regex("""\battach(?:ed|ment|ments|ing|es)?\b""", RegexOption.IGNORE_CASE)

    init {
        if (draftId != null) {
            viewModelScope.launch {
                mailRepository.getDraft(draftId)?.let { draft ->
                    _state.update {
                        it.copy(
                            to = draft.to,
                            cc = draft.cc,
                            bcc = draft.bcc,
                            subject = draft.subject,
                            body = draft.body,
                            bodyHtml = draft.bodyHtml,
                            fromAccountId = draft.accountId ?: it.fromAccountId,
                            attachments = draft.attachments,
                        )
                    }
                }
            }
        } else {
            // New composition (incl. reader-reply, which prefills From): append the sending account's
            // signature. Reply/forward drafts already carry theirs, so they take the draft branch above.
            viewModelScope.launch {
                val available = accountRepository.observeAccounts().first { it.isNotEmpty() }
                // The persisted default (#163) only counts if it still names an account that exists.
                // Deleting the default account normally clears this via
                // SettingsRepository.clearDefaultAccountId, but a stale id could still reach here (e.g.
                // a Backup restore onto a device that never had the account) — validate rather than
                // trust it, so it just falls through to the incidental "first account alphabetically"
                // behavior instead of crashing or composing from a nonexistent account.
                val defaultAccountId = settingsRepository.settings.first().defaultAccountId
                val validDefaultAccountId = defaultAccountId?.takeIf { id -> available.any { it.id == id } }
                val effectiveId = _state.value.fromAccountId ?: validDefaultAccountId ?: available.first().id
                applySignature(effectiveId)
            }
        }
    }

    fun onToChange(value: String) {
        _state.update { it.copy(to = value) }
        searchContacts(value)
    }

    fun onCcChange(value: String) = _state.update { it.copy(cc = value) }
    fun onBccChange(value: String) = _state.update { it.copy(bcc = value) }
    fun onSubjectChange(value: String) = _state.update { it.copy(subject = value) }

    /**
     * The rich editor reports the current body in both forms: [plain] (also the plaintext fallback)
     * and [html], which is null when the content carries no formatting so the message stays
     * plaintext-only. Both are held for sending and for saving the draft.
     */
    fun onBodyChange(plain: String, html: String?) = _state.update { it.copy(body = plain, bodyHtml = html) }

    fun selectFrom(accountId: String) {
        viewModelScope.launch { applySignature(accountId) }
    }

    /**
     * Sets the sending account and swaps its default signature into the body: strips the
     * previously-appended block (when the body still ends with it) and appends the newly-selected
     * account's, in both the plaintext and HTML representations. Honors the account's
     * "append signature" preference.
     */
    private suspend fun applySignature(accountId: String) {
        val settings = accountSettingsRepository.get(accountId)
        val block = if (settings.signatureEnabled) {
            SignatureBlock.of(signatureRepository.getDefault(accountId))
        } else {
            SignatureBlock.EMPTY
        }
        _state.update { s ->
            val basePlain = s.body.stripSuffixIfPresent(appliedSignatureBlock.plain)
            val newBody = basePlain + block.plain
            s.copy(fromAccountId = accountId, body = newBody, bodyHtml = swapHtmlSignature(s.bodyHtml, newBody, block))
        }
        appliedSignatureBlock = block
    }

    /**
     * Swaps the signature in the HTML body. When the old block is still a clean suffix (the common
     * case — the user changed accounts before editing), it is stripped and the new one appended,
     * preserving any formatting the user applied. Otherwise the HTML was re-serialized after editing
     * and no longer ends with the old block, so it is rebuilt from the plaintext to avoid ever
     * duplicating the signature (inline styling from before the switch is not preserved in that case).
     */
    private fun swapHtmlSignature(currentHtml: String?, newBody: String, block: SignatureBlock): String? {
        val old = appliedSignatureBlock.html
        val cleanlyStrippable = old.isEmpty() || currentHtml == null || currentHtml.endsWith(old)
        val combined = if (cleanlyStrippable) {
            (currentHtml?.removeSuffix(old) ?: "") + block.html
        } else {
            RichTextHtml.toHtml(RichTextContent(newBody))
        }
        return normalizedHtml(combined)
    }

    private fun String.stripSuffixIfPresent(suffix: String): String =
        if (suffix.isNotEmpty() && endsWith(suffix)) removeSuffix(suffix) else this

    /** Keeps an HTML body only when it actually carries formatting, so plaintext stays plaintext. */
    private fun normalizedHtml(html: String): String? =
        if (html.isBlank() || !RichTextHtml.fromHtml(html).hasFormatting()) null else html
    fun addAttachments(items: List<OutgoingAttachment>) =
        _state.update { it.copy(attachments = it.attachments + items) }
    fun removeAttachment(uri: String) = _state.update {
        it.copy(
            attachments = it.attachments.filterNot { a ->
                a.uri ==
                    uri
            },
        )
    }
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
        if (navigated) return
        viewModelScope.launch {
            // Don't save a draft for a message that's mid-send (send() will finish the screen).
            if (!_state.value.sending) saveOrDeleteDraft()
            finish()
        }
    }

    /** Closes the screen exactly once, so send() and a stray back-press can't double-pop. */
    private suspend fun finish() {
        if (navigated) return
        navigated = true
        _finished.send(Unit)
    }

    private suspend fun saveOrDeleteDraft() {
        val s = _state.value
        val hasContent = s.to.isNotBlank() ||
            s.cc.isNotBlank() ||
            s.bcc.isNotBlank() ||
            s.subject.isNotBlank() ||
            s.body.isNotBlank() ||
            s.attachments.isNotEmpty()
        when {
            hasContent -> mailRepository.saveDraft(
                Draft(
                    id = draftId ?: UUID.randomUUID().toString(),
                    accountId = s.fromAccountId,
                    to = s.to,
                    cc = s.cc,
                    bcc = s.bcc,
                    subject = s.subject,
                    body = s.body,
                    updatedAt = System.currentTimeMillis(),
                    bodyHtml = s.bodyHtml,
                    attachments = s.attachments,
                ),
            )
            draftId != null -> mailRepository.deleteDraft(draftId) // an existing draft was emptied out
        }
    }

    fun send() = trySend(checkAttachments = true)

    /** "No" on the attachment prompt — the user confirmed nothing needs attaching, so send as-is. */
    fun sendAnyway() {
        _state.update { it.copy(showAttachmentPrompt = false) }
        trySend(checkAttachments = false)
    }

    /** "Yes" on the attachment prompt — back to composing, with the attach button highlighted. */
    fun attachInstead() = _state.update { it.copy(showAttachmentPrompt = false, highlightAttach = true) }

    /** The prompt was dismissed without choosing: stay composing, no send, no highlight. */
    fun dismissAttachmentPrompt() = _state.update { it.copy(showAttachmentPrompt = false) }

    /** The attach-button highlight has finished animating. */
    fun consumeAttachHighlight() = _state.update { it.copy(highlightAttach = false) }

    private fun trySend(checkAttachments: Boolean) {
        viewModelScope.launch {
            val s = _state.value
            // Await the account list if it hasn't emitted yet, so an early tap doesn't wrongly
            // report "Add an account first".
            val available = accounts.value.ifEmpty { accountRepository.observeAccounts().first() }
            val account = available.firstOrNull { it.id == s.fromAccountId } ?: available.firstOrNull()
            when {
                account == null -> _state.update { it.copy(error = "Add an account first") }
                s.to.isBlank() -> _state.update { it.copy(error = "Add a recipient") }
                checkAttachments && s.attachments.isEmpty() && mentionsAttachment(s) ->
                    _state.update { it.copy(showAttachmentPrompt = true) }
                else -> performSend(account, s)
            }
        }
    }

    /** The classic forgotten-attachment guard: does the subject or body talk about attaching? */
    private fun mentionsAttachment(s: ComposeUiState): Boolean =
        attachmentMention.containsMatchIn(s.subject) || attachmentMention.containsMatchIn(s.body)

    private suspend fun performSend(account: Account, s: ComposeUiState) {
        _state.update { it.copy(sending = true, error = null) }
        mailRepository.sendMessage(
            OutgoingMessage(
                accountId = account.id,
                to = s.to,
                cc = s.cc,
                bcc = s.bcc,
                subject = s.subject,
                body = s.body,
                bodyHtml = s.bodyHtml,
                attachments = s.attachments,
            ),
        ).fold(
            onSuccess = {
                draftId?.let { mailRepository.deleteDraft(it) }
                _state.update { it.copy(sending = false) }
                finish()
            },
            onFailure = { e ->
                _state.update { it.copy(sending = false, error = e.message ?: "Could not send") }
            },
        )
    }
}
