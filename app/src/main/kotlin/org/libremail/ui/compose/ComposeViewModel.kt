// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.compose

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
import org.libremail.richtext.RichBaseStyle
import org.libremail.richtext.RichStyle
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
    /**
     * A just-picked inline image waiting for the editor to drop its token at the caret. The editor
     * consumes it (via [ComposeViewModel.onInlineImageInserted]) once inserted; a transient signal, so
     * it is excluded from the autosaved [DraftContent].
     */
    val pendingInlineImage: PendingInlineImage? = null,
)

/** A picked inline image the editor should insert: the token to show and the [contentId] to link it by. */
data class PendingInlineImage(val contentId: String, val name: String)

/** The persisted draft fields; autosave fires only when one of these actually changes (#177). */
private data class DraftContent(
    val to: String,
    val cc: String,
    val bcc: String,
    val subject: String,
    val body: String,
    val bodyHtml: String?,
    val attachments: List<OutgoingAttachment>,
)

/** Idle window after the last edit before an in-progress compose draft is autosaved (#177). */
private const val DRAFT_AUTOSAVE_DEBOUNCE_MS = 1_500L

@OptIn(FlowPreview::class)
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

    /**
     * The id every save in this session writes under: the resumed draft's [draftId], or — for a
     * brand-new composition — a single id generated once and reused for every autosave (#177). Without
     * this, `id = draftId ?: UUID.randomUUID()` minted a fresh id on each call, so periodic autosave
     * would insert a new duplicate draft row per tick instead of updating the same one.
     */
    private val persistedDraftId: String by lazy { draftId ?: UUID.randomUUID().toString() }

    /** Whether a draft row currently exists for this session (resumed, or autosaved at least once). */
    private var draftPersisted: Boolean = draftId != null

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
                val settings = settingsRepository.settings.first()
                // The persisted default (#163) only counts if it still names an account that exists.
                // Deleting the default account normally clears this via
                // SettingsRepository.clearDefaultAccountId, but a stale id could still reach here (e.g.
                // a Backup restore onto a device that never had the account) — validate rather than
                // trust it, so it just falls through to the incidental "first account alphabetically"
                // behavior instead of crashing or composing from a nonexistent account.
                val validDefaultAccountId = settings.defaultAccountId?.takeIf { id -> available.any { it.id == id } }
                val effectiveId = _state.value.fromAccountId ?: validDefaultAccountId ?: available.first().id
                applySignature(effectiveId)
                // Seed the message-wide default font (#78) after the signature is applied, so the whole
                // body — including the signature just appended — picks it up. New compositions only:
                // replies/forwards resume as drafts and take the branch above, left untouched.
                seedRememberedFont(settings.lastFontCss, settings.lastFontSizePt)
            }
        }
        // Debounced periodic autosave (#177): coalesce rapid edits to the persisted fields into one
        // write after a short idle window, so an in-progress draft survives a background-kill without
        // waiting for the explicit back-press save. Keyed only off the fields saveOrDeleteDraft() writes
        // (from-account and transient UI flags are intentionally excluded).
        viewModelScope.launch {
            _state
                .map { DraftContent(it.to, it.cc, it.bcc, it.subject, it.body, it.bodyHtml, it.attachments) }
                .distinctUntilChanged()
                .drop(1)
                .debounce(DRAFT_AUTOSAVE_DEBOUNCE_MS)
                .collect { autosaveDraft() }
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
     *
     * Inline images are reconciled against the body here: an inline attachment is kept only while its
     * `cid:` is still referenced by the HTML (or is the one currently being inserted), so deleting an
     * image's `[image: …]` token drops the image itself. Regular attachments are never touched.
     */
    fun onBodyChange(plain: String, html: String?) = _state.update { s ->
        val referenced = referencedContentIds(html)
        val keptAttachments = s.attachments.filter { attachment ->
            !attachment.isInline ||
                attachment.contentId in referenced ||
                attachment.contentId == s.pendingInlineImage?.contentId
        }
        s.copy(body = plain, bodyHtml = html, attachments = keptAttachments)
    }

    /** The content ids the body's HTML still references (`cid:…`), used to prune deleted inline images. */
    private fun referencedContentIds(html: String?): Set<String> =
        html?.let { RichTextHtml.fromHtml(it).images.mapTo(mutableSetOf()) { image -> image.contentId } }.orEmpty()

    /**
     * A picked inline image: track it as an inline attachment (alongside regular ones) and hand the
     * editor a [PendingInlineImage] to drop at the caret. The generated content id ties the body's
     * `cid:` reference to the attachment the sender emits with a matching `Content-ID`.
     */
    fun onImagePicked(uri: String, name: String) {
        val contentId = "img-${UUID.randomUUID()}@libremail"
        _state.update {
            it.copy(
                attachments = it.attachments + OutgoingAttachment(uri, name, contentId, isInline = true),
                pendingInlineImage = PendingInlineImage(contentId, name),
            )
        }
    }

    /** The editor has inserted the pending image's token; clear the transient signal. */
    fun onInlineImageInserted() = _state.update { it.copy(pendingInlineImage = null) }

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

    /**
     * Seeds a brand-new composition with the last-remembered font (#78) by wrapping the current body
     * in a [RichBaseStyle], so the whole message — including any signature just applied — defaults to
     * it. A no-op when nothing has been remembered yet, so a message that would otherwise stay
     * plaintext-only isn't forced into an HTML body for no visible reason (an empty [RichBaseStyle]
     * still flips [RichTextContent.hasFormatting]).
     */
    private fun seedRememberedFont(fontCss: String?, fontSizePt: Int?) {
        if (fontCss == null && fontSizePt == null) return
        _state.update { s ->
            val content = (s.bodyHtml?.let(RichTextHtml::fromHtml) ?: RichTextContent(text = s.body))
                .copy(baseStyle = RichBaseStyle(fontCss, fontSizePt))
            s.copy(bodyHtml = RichTextHtml.toHtml(content))
        }
    }

    /**
     * Remembers the font used in a just-sent formatted message (#78): the message-wide base style if
     * one is set, else the last `FontFamily`/`FontSize` span in the body (the formatting nearest the
     * end of the message). A no-op for a plaintext send ([bodyHtml] null) or a formatted one that never
     * touched the font — an unrelated send must never clear a previously remembered preference.
     */
    private suspend fun rememberFontPreference(bodyHtml: String?) {
        val html = bodyHtml ?: return
        val (fontCss, fontSizePt) = lastFontIn(RichTextHtml.fromHtml(html)) ?: return
        settingsRepository.setLastFont(fontCss, fontSizePt)
    }

    /** The base style if set, else the last matching inline font span; null when neither is present. */
    private fun lastFontIn(content: RichTextContent): Pair<String?, Int?>? {
        content.baseStyle?.let { return it.fontCss to it.fontSizePt }
        val css = content.spans.lastOrNull { it.style is RichStyle.FontFamily }
            ?.let { (it.style as RichStyle.FontFamily).css }
        val sizePt = content.spans.lastOrNull { it.style is RichStyle.FontSize }
            ?.let { (it.style as RichStyle.FontSize).pt }
        return if (css != null || sizePt != null) css to sizePt else null
    }

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

    /**
     * Immediately flushes any pending draft save — called from ComposeScreen on `ON_STOP` so the last
     * keystrokes within the debounce window aren't lost if the app is killed while backgrounded (#177).
     */
    fun flushDraft() {
        viewModelScope.launch { autosaveDraft() }
    }

    /**
     * Persists (or deletes, when emptied) the draft now — unless a send is in flight or we've already
     * navigated away. Mirrors onExit()'s "don't save mid-send" guard and never re-creates a draft for
     * an already-sent message (see [navigated]). Shared by the debounce collector and [flushDraft].
     */
    private suspend fun autosaveDraft() {
        if (!_state.value.sending && !navigated) saveOrDeleteDraft()
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
            hasContent -> {
                mailRepository.saveDraft(
                    Draft(
                        id = persistedDraftId,
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
                draftPersisted = true
            }
            // The draft was emptied out: drop the persisted row — a resumed draft, or one an earlier
            // autosave tick created this session — so an empty orphan is never left behind.
            draftPersisted -> {
                mailRepository.deleteDraft(persistedDraftId)
                draftPersisted = false
            }
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
                rememberFontPreference(s.bodyHtml)
                // Delete the draft this message was composed from — whether resumed from the drafts list
                // or created by an autosave tick this session (#177) — so sending never orphans it.
                if (draftPersisted) mailRepository.deleteDraft(persistedDraftId)
                draftPersisted = false
                _state.update { it.copy(sending = false) }
                finish()
            },
            onFailure = { e ->
                _state.update { it.copy(sending = false, error = e.message ?: "Could not send") }
            },
        )
    }
}
