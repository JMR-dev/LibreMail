// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.drafts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.libremail.domain.model.Draft
import org.libremail.domain.repository.MailRepository
import javax.inject.Inject

@HiltViewModel
class DraftsViewModel @Inject constructor(private val repository: MailRepository) : ViewModel() {

    val drafts: StateFlow<List<Draft>> = repository.observeDrafts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun deleteDraft(id: String) {
        viewModelScope.launch { repository.deleteDraft(id) }
    }
}
