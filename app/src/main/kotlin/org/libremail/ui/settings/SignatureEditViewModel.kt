// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.libremail.data.settings.SignatureRepository
import org.libremail.richtext.RichTextContent
import org.libremail.richtext.RichTextHtml
import org.libremail.ui.navigation.Routes
import javax.inject.Inject

data class SignatureEditUiState(
    val name: String = "",
    val body: String = "",
    val bodyHtml: String? = null,
    val loaded: Boolean = false,
)

@HiltViewModel
class SignatureEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val signatureRepository: SignatureRepository,
) : ViewModel() {

    private val accountId: String = checkNotNull(savedStateHandle[Routes.SIGNATURE_EDIT_ARG_ACCOUNT])
    private val signatureId: String? =
        savedStateHandle.get<String>(Routes.SIGNATURE_EDIT_ARG_ID)?.takeIf { it.isNotBlank() }

    val isNew: Boolean = signatureId == null

    private val _state = MutableStateFlow(SignatureEditUiState(loaded = isNew))
    val state: StateFlow<SignatureEditUiState> = _state.asStateFlow()

    init {
        if (signatureId != null) {
            viewModelScope.launch {
                signatureRepository.get(signatureId)?.let { signature ->
                    _state.update {
                        it.copy(
                            name = signature.name,
                            body = signature.plainText(),
                            bodyHtml = signature.html.ifBlank { null },
                            loaded = true,
                        )
                    }
                }
            }
        }
    }

    fun onNameChange(value: String) = _state.update { it.copy(name = value) }

    fun onBodyChange(plain: String, html: String?) = _state.update { it.copy(body = plain, bodyHtml = html) }

    /** Persists the signature (create or update), then invokes [onSaved]. */
    fun save(onSaved: () -> Unit) {
        val s = _state.value
        val name = s.name.trim().ifBlank { DEFAULT_NAME }
        // Store real HTML so the signature round-trips; derive it from the plaintext when unformatted.
        val html = s.bodyHtml ?: RichTextHtml.toHtml(RichTextContent(s.body))
        viewModelScope.launch {
            if (signatureId == null) {
                signatureRepository.create(accountId, name, html)
            } else {
                signatureRepository.update(signatureId, name, html)
            }
            onSaved()
        }
    }

    private companion object {
        const val DEFAULT_NAME = "Signature"
    }
}
