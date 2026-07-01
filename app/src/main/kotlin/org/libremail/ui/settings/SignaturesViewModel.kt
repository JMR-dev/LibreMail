// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.libremail.data.settings.SignatureRepository
import org.libremail.domain.model.Signature
import org.libremail.ui.navigation.Routes
import javax.inject.Inject

@HiltViewModel
class SignaturesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val signatureRepository: SignatureRepository,
) : ViewModel() {

    val accountId: String = checkNotNull(savedStateHandle[Routes.SIGNATURES_ARG_ACCOUNT])

    val signatures: StateFlow<List<Signature>> = signatureRepository.observeForAccount(accountId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setDefault(id: String) {
        viewModelScope.launch { signatureRepository.setDefault(accountId, id) }
    }

    fun delete(id: String) {
        viewModelScope.launch { signatureRepository.delete(id) }
    }
}
