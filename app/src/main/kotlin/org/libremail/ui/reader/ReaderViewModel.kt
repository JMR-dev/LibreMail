// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.libremail.data.settings.SettingsRepository
import org.libremail.domain.model.Attachment
import org.libremail.domain.model.InlineImage
import org.libremail.domain.model.Message
import org.libremail.domain.model.ReplyMode
import org.libremail.domain.repository.MailRepository
import org.libremail.reporting.AppLog
import org.libremail.ui.navigation.Routes
import java.io.File
import javax.inject.Inject

data class ReaderUiState(
    val loading: Boolean = true,
    val message: Message? = null,
    val attachments: List<Attachment> = emptyList(),
    /** Inline `cid:` images for the HTML body, keyed by normalized Content-ID (see [HtmlBody]). */
    val inlineImages: Map<String, InlineImage> = emptyMap(),
    val downloading: Set<Int> = emptySet(),
    /** Part indexes whose bytes are already cached on disk (openable offline). */
    val downloaded: Set<Int> = emptySet(),
    val loadRemoteImages: Boolean = false,
    val deleted: Boolean = false,
    /** True while a reply/forward draft is being built (a brief network round-trip); gates re-entry. */
    val composing: Boolean = false,
    val error: String? = null,
)

/** One-shot effects the reader screen acts on (launching a viewer, opening compose, showing a message). */
sealed interface ReaderEvent {
    data class OpenFile(val file: File, val mimeType: String, val name: String) : ReaderEvent
    data class DownloadFailed(val name: String) : ReaderEvent

    /** A reply/forward draft was built; the screen opens compose on it. */
    data class OpenCompose(val draftId: String) : ReaderEvent

    /** Building the reply/forward draft failed; the screen surfaces [message] (or a generic fallback). */
    data class ComposeFailed(val message: String?) : ReaderEvent

    /** Persisting a star toggle failed; the optimistic flip was rolled back and the screen notifies. */
    data object StarFailed : ReaderEvent
}

@HiltViewModel
class ReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MailRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val messageId: String = checkNotNull(savedStateHandle[Routes.READER_ARG_ID])

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private val _events = Channel<ReaderEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        // Honor the global "load remote images by default" preference.
        viewModelScope.launch {
            if (settingsRepository.settings.first().loadRemoteImages) {
                _state.update { it.copy(loadRemoteImages = true) }
            }
        }
        viewModelScope.launch {
            // Time the spinner: from launch to the state update that clears `loading` — what the user
            // actually waits through. Covers openMessage (first-open body fetch) plus inline-image
            // resolution, so a slow render can be split from a slow open in a debug report (issue #358).
            val startNanos = System.nanoTime()
            repository.openMessage(messageId).fold(
                onSuccess = { message ->
                    // Resolve inline cid: images BEFORE the first render and publish them in the SAME
                    // state update as the body, so the reader's WebView loads exactly once instead of
                    // rendering with an empty image map and reloading when they arrive (issue #186).
                    // openMessage has already cached the parts; plain-text mail has none to resolve.
                    val images = if (message.isHtml) {
                        repository.inlineImages(messageId).associateBy { it.contentId }
                    } else {
                        emptyMap()
                    }
                    _state.update { it.copy(loading = false, message = message, inlineImages = images) }
                    AppLog.d(
                        READER_TAG,
                        "reader ready took=${(System.nanoTime() - startNanos) / NANOS_PER_MS}ms " +
                            "html=${message.isHtml} inline=${images.size}",
                    )
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(
                            loading = false,
                            error =
                            e.message ?: "Could not load message",
                        )
                    }
                    AppLog.w(
                        READER_TAG,
                        "reader load failed took=${(System.nanoTime() - startNanos) / NANOS_PER_MS}ms",
                    )
                },
            )
        }
        viewModelScope.launch {
            repository.observeAttachments(messageId).collect { attachments ->
                _state.update {
                    it.copy(attachments = attachments, downloaded = repository.downloadedAttachmentParts(messageId))
                }
            }
        }
    }

    fun downloadAttachment(attachment: Attachment) {
        if (attachment.partIndex in _state.value.downloading) return
        _state.update { it.copy(downloading = it.downloading + attachment.partIndex) }
        viewModelScope.launch {
            repository.downloadAttachment(attachment.messageId, attachment.partIndex).fold(
                onSuccess = { file ->
                    _state.update { it.copy(downloaded = it.downloaded + attachment.partIndex) }
                    _events.send(ReaderEvent.OpenFile(file, attachment.mimeType, attachment.filename))
                },
                onFailure = { _events.send(ReaderEvent.DownloadFailed(attachment.filename)) },
            )
            _state.update { it.copy(downloading = it.downloading - attachment.partIndex) }
        }
    }

    fun toggleStar() {
        val message = _state.value.message ?: return
        val starred = !message.isStarred
        _state.update { it.copy(message = message.copy(isStarred = starred)) }
        viewModelScope.launch {
            repository.setStarred(messageId, starred).onFailure { e ->
                // The optimistic flip already updated the UI; the persist failed, so reconcile by rolling
                // it back (the star would otherwise stay stuck in a state the store never accepted) and
                // notify the screen. Revert to the pre-toggle value (!starred) of the current message.
                AppLog.w(READER_TAG, "toggleStar persist failed; rolling back optimistic star", e)
                _state.update { current ->
                    val shown = current.message ?: return@update current
                    current.copy(message = shown.copy(isStarred = !starred))
                }
                _events.send(ReaderEvent.StarFailed)
            }
        }
    }

    fun loadRemoteImages() = _state.update { it.copy(loadRemoteImages = true) }

    /**
     * Builds a reply/reply-all/forward draft from the open message and opens compose on it — the same
     * high-fidelity path the mailbox uses (quotes the original into a `<blockquote>`, bakes the
     * signature, prefixes Re:/Fwd:), instead of a bare prefill (#303). [composing] is flipped
     * synchronously before the launch so a double-tap can't build two drafts.
     */
    fun reply(mode: ReplyMode) {
        if (_state.value.composing) return
        _state.update { it.copy(composing = true) }
        viewModelScope.launch {
            repository.buildReplyDraft(messageId, mode).fold(
                onSuccess = { draftId -> _events.send(ReaderEvent.OpenCompose(draftId)) },
                onFailure = { e -> _events.send(ReaderEvent.ComposeFailed(e.message)) },
            )
            _state.update { it.copy(composing = false) }
        }
    }

    fun delete() {
        viewModelScope.launch {
            repository.deleteMessage(messageId)
            _state.update { it.copy(deleted = true) }
        }
    }

    private companion object {
        const val READER_TAG = "Reader"
        const val NANOS_PER_MS = 1_000_000L
    }
}
