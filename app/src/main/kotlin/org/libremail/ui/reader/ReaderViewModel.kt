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
import org.libremail.domain.repository.MailRepository
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
    val error: String? = null,
)

/** One-shot effects the reader screen acts on (launching a viewer, showing a message). */
sealed interface ReaderEvent {
    data class OpenFile(val file: File, val mimeType: String, val name: String) : ReaderEvent
    data class DownloadFailed(val name: String) : ReaderEvent
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
            repository.openMessage(messageId).fold(
                onSuccess = { message ->
                    _state.update { it.copy(loading = false, message = message) }
                    // Resolve inline cid: images so the WebView can embed them. Runs after openMessage
                    // has cached the parts; skipped for plain-text mail and messages with none.
                    if (message.isHtml) {
                        val images = repository.inlineImages(messageId).associateBy { it.contentId }
                        if (images.isNotEmpty()) _state.update { it.copy(inlineImages = images) }
                    }
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(
                            loading = false,
                            error =
                            e.message ?: "Could not load message",
                        )
                    }
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
        viewModelScope.launch { repository.setStarred(messageId, starred) }
    }

    fun loadRemoteImages() = _state.update { it.copy(loadRemoteImages = true) }

    fun delete() {
        viewModelScope.launch {
            repository.deleteMessage(messageId)
            _state.update { it.copy(deleted = true) }
        }
    }
}
