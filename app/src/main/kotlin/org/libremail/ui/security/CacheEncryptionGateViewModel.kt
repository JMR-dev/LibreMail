// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.security

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.libremail.data.local.CacheEncryptionUnavailableException
import org.libremail.data.local.DatabaseProvisioner
import org.libremail.reporting.AppLog
import org.libremail.reporting.DiagnosticsCollector
import javax.inject.Inject

/** State of the fail-closed encrypted-cache gate that wraps the app (issue #359). */
sealed interface CacheEncryptionGateState {
    /** Resolving whether the encrypted cache can be opened — render a blank cover, never the app. */
    data object Checking : CacheEncryptionGateState

    /** The cache is openable (encrypted-and-ready, or not encrypted): show the app content. */
    data object Ready : CacheEncryptionGateState

    /**
     * SQLCipher's native library will not load, so the encrypted cache cannot be opened. FAIL CLOSED:
     * show the error gate instead of the mailbox — never an unencrypted cache.
     */
    data object Unavailable : CacheEncryptionGateState
}

/**
 * Drives the fail-closed encrypted-cache gate. On first composition it proactively runs the shared
 * startup sequence ([DatabaseProvisioner.prepareCache]) so the cache's open mode is resolved BEFORE any
 * DB-backed screen composes. If that raises [CacheEncryptionUnavailableException] — SQLCipher's native
 * library failed to load (issue #359) — the gate goes to [CacheEncryptionGateState.Unavailable] and the
 * host shows the encryption error screen; otherwise it goes [CacheEncryptionGateState.Ready] and the app
 * renders. The failure is never memoized by the provisioner, so a fresh process (e.g. after an app
 * update that ships a loadable library) re-probes and recovers automatically.
 *
 * From the error screen the user can generate an **ephemeral** PII-free diagnostic report
 * ([prepareReport]) reusing the app's existing [DiagnosticsCollector]. Because encryption is
 * unavailable in this exact moment the report cannot be encrypted at rest, so it is deliberately NOT
 * written to `ReportStore` — it lives only in memory for on-screen review and the user's explicit
 * Copy/Save.
 *
 * This VM must be hosted only AFTER the app-lock gate unlocks (see `AppLockGateHost`), so when app-lock
 * is on the auth-bound passphrase is already in [org.libremail.data.security.PassphraseSession] and
 * `prepareCache()` does not park waiting for authentication.
 */
@HiltViewModel
class CacheEncryptionGateViewModel @Inject constructor(
    private val provisioner: DatabaseProvisioner,
    private val diagnostics: DiagnosticsCollector,
) : ViewModel() {

    // Injectable so the report collection (which touches DataStore + the account store) is pushed off
    // the main thread in production yet runs on the test scheduler in unit tests. prepareCache() already
    // switches to its own IO dispatcher internally, so the probe does not need this.
    @VisibleForTesting
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private val _state = MutableStateFlow<CacheEncryptionGateState>(CacheEncryptionGateState.Checking)
    val state: StateFlow<CacheEncryptionGateState> = _state.asStateFlow()

    // The ephemeral report payload, or null before it has been generated / after dismissal. Never
    // persisted — it exists only for on-screen review and the user's explicit Copy/Save.
    private val _reportPayload = MutableStateFlow<String?>(null)
    val reportPayload: StateFlow<String?> = _reportPayload.asStateFlow()

    init {
        probe()
    }

    private fun probe() {
        viewModelScope.launch {
            _state.value = try {
                provisioner.prepareCache()
                CacheEncryptionGateState.Ready
            } catch (e: CacheEncryptionUnavailableException) {
                AppLog.w(TAG, "encrypted cache unavailable; showing the fail-closed encryption gate", e)
                CacheEncryptionGateState.Unavailable
            }
        }
    }

    /**
     * Generate the ephemeral PII-free diagnostic report for on-screen review (idempotent while one is
     * already prepared). Reuses [DiagnosticsCollector.collectManual] — the same PII-free assembly the
     * normal "Report a problem" flow uses — but the result is held only in memory here, never saved.
     */
    fun prepareReport() {
        if (_reportPayload.value != null) return
        viewModelScope.launch {
            _reportPayload.value = withContext(ioDispatcher) { diagnostics.collectManual().toSubmissionPayload() }
        }
    }

    /** Drop the in-memory report (on leaving the review screen). */
    fun dismissReport() {
        _reportPayload.value = null
    }

    private companion object {
        const val TAG = "CacheEncryptionGate"
    }
}
