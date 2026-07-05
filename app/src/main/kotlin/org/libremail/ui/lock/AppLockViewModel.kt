// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.lock

import android.content.Context
import android.os.SystemClock
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Operation
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.libremail.R
import org.libremail.data.security.AppLockGate
import org.libremail.data.security.AppLockManager
import org.libremail.data.security.DatabaseKeyCipher
import org.libremail.data.security.DatabaseKeyStore
import org.libremail.data.security.KeyInvalidationPolicy
import org.libremail.data.security.LockAction
import org.libremail.data.security.LockState
import org.libremail.data.security.PassphraseSession
import org.libremail.data.settings.SettingsRepository
import org.libremail.data.sync.SyncScheduler
import org.libremail.reporting.AppLog
import org.libremail.restart.ProcessRestarter
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.inject.Inject

/** UI state of the app-lock gate that wraps the whole app. */
sealed interface AppLockUiState {
    /** Still resolving settings / device state — render a blank surface, never the app content. */
    data object Checking : AppLockUiState

    /** App-lock is off or the user has authenticated: show the app. */
    data object Unlocked : AppLockUiState

    /**
     * Show the lock screen; [error] is a human-readable reason the previous attempt failed. [nonce]
     * makes each lock emission distinct so a retry (same or null error text) still updates the UI
     * rather than being swallowed by StateFlow's equality conflation.
     */
    data class Locked(val error: String? = null, val nonce: Int = 0) : AppLockUiState
}

/**
 * Drives the app-lock gate: reconciles the app-lock / encrypted-cache settings with device state on
 * every foreground pass, requests authentication, and on success unwraps the auth-bound SQLCipher
 * passphrase into [PassphraseSession] so the encrypted cache becomes readable.
 *
 * Key invalidation (biometric re-enrollment or lock removal) is handled by "clear + re-sync, never
 * corrupt": the cache cannot be deleted here because Room may hold it open, so we persist a flag and
 * restart the process — `DatabaseModule.provideDatabase` wipes the file at the next cold start, before
 * Room opens it. The [AppLockGate] state machine and [KeyInvalidationPolicy] decision table (both
 * pure) carry the security-critical branching and are unit-tested; this ViewModel wires them to
 * Android/crypto.
 *
 * Threading: coroutines run on the main dispatcher so [gate] (also touched by [onBackground] from the
 * lifecycle callback) is only ever mutated on the main thread; blocking Keystore/DataStore work is
 * pushed to [Dispatchers.Default] via [withContext].
 */
@HiltViewModel
class AppLockViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val appLockManager: AppLockManager,
    private val databaseKeyStore: DatabaseKeyStore,
    private val databaseKeyCipher: DatabaseKeyCipher,
    private val session: PassphraseSession,
    private val syncScheduler: SyncScheduler,
    // Issues the key-invalidation recovery relaunch from a separate ":restart" process that survives
    // this process being killed, so the relaunch can't be dropped by ActivityManager scheduling it
    // into the dying process (the same-process "startActivity then exit(0)" race).
    private val processRestarter: ProcessRestarter,
    // Application-scoped (see SecurityModule): the gate is injected rather than owned by this
    // Activity-scoped ViewModel so the inactivity grace window survives Activity recreation — Back on
    // the task root finishes the Activity and clears its ViewModelStore on API 29/30, which would
    // otherwise drop the grace marker and force a full re-auth on return within the grace period.
    private val gate: AppLockGate,
) : ViewModel() {

    private val _uiState = MutableStateFlow<AppLockUiState>(AppLockUiState.Checking)
    val uiState: StateFlow<AppLockUiState> = _uiState.asStateFlow()

    // The dispatcher for blocking Keystore/DataStore/WorkManager work pushed off the main thread.
    // Injectable so the recovery flow (clearCacheAndRestart) runs on the test scheduler and its
    // ordering — enqueue durably persisted BEFORE the restart — is deterministically verifiable.
    @VisibleForTesting
    internal var defaultDispatcher: CoroutineDispatcher = Dispatchers.Default

    // Cached so onBackground / onForeground can cover the content synchronously (before the async
    // settings read) whenever app-lock is on — so no stale mailbox frame renders on resume.
    @Volatile private var appLockEnabledCached = false

    // Bumped on every lock emission so two consecutive locks with identical error text still differ
    // (StateFlow conflates equal values), guaranteeing the lock screen updates — e.g. on a retry.
    private var lockSeq = 0

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { appLockEnabledCached = it.appLock }
        }
    }

    /**
     * Single writer for the gated UI state: derive it from [AppLockGate.state] — the source of truth
     * for whether the session is locked — instead of hand-mirroring the gate at each call site. [error]
     * is a human-readable reason the previous unlock attempt failed. The transient
     * [AppLockUiState.Checking] cover and the "app-lock off / just disabled" unlocked states are driven
     * by settings/lifecycle rather than the session gate, so their sites set [_uiState] directly.
     */
    private fun publish(error: String? = null) {
        _uiState.value = when (gate.state) {
            LockState.UNLOCKED -> AppLockUiState.Unlocked
            LockState.LOCKED -> AppLockUiState.Locked(error, ++lockSeq)
        }
    }

    /** Recompute the lock state when the app comes to the foreground (lifecycle ON_START). */
    fun onForeground() {
        // Capture the foreground timestamp synchronously (before any suspension) so a concurrent
        // onBackground can't corrupt the grace calculation for this pass (see AppLockGate.onForeground).
        val foregroundAt = now()
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            if (!settings.appLock) {
                _uiState.value = AppLockUiState.Unlocked
                return@launch
            }
            // App-lock is on: cover any showing content while we resolve, so no stale mailbox frame
            // renders before the (async) decision lands.
            if (_uiState.value == AppLockUiState.Unlocked) _uiState.value = AppLockUiState.Checking
            val action = withContext(defaultDispatcher) {
                KeyInvalidationPolicy.decide(
                    appLockEnabled = true,
                    encryptCacheEnabled = settings.encryptCache,
                    deviceSecure = appLockManager.isDeviceSecure(),
                    keyInvalidated = databaseKeyCipher.isInvalidated(),
                )
            }
            // LockAction is a non-PII enum, so it's safe to record verbatim as a breadcrumb.
            AppLog.i(TAG, "app-lock foreground decision: $action")
            when (action) {
                LockAction.PROCEED -> _uiState.value = AppLockUiState.Unlocked

                LockAction.DISABLE_APP_LOCK -> {
                    settingsRepository.setAppLock(false)
                    _uiState.value = AppLockUiState.Unlocked
                }

                LockAction.CLEAR_AND_DISABLE -> clearCacheAndRestart(disableAppLock = true)

                LockAction.CLEAR_AND_REQUIRE_AUTH -> clearCacheAndRestart(disableAppLock = false)

                LockAction.REQUIRE_AUTH -> {
                    // Advance the gate for this foreground pass, then publish its decision (UNLOCKED
                    // within the grace window, otherwise LOCKED). Re-locking on grace expiry
                    // intentionally does NOT evict the SQLCipher passphrase: PassphraseSession is the
                    // only separately-held copy, but clearing it flips EncryptedCacheGuard to "locked"
                    // and would stall background sync/push while locked, and the already-open Room handle
                    // keeps the key resident regardless. Full eviction needs the DB close/reopen owned by
                    // #93 / #111 — see PassphraseSession's KDoc.
                    gate.onForeground(foregroundAt, appLockEnabled = true)
                    publish()
                }
            }
        }
    }

    /** Record the app being backgrounded so the inactivity grace period can be evaluated on return. */
    fun onBackground() {
        gate.onBackground(now())
        // Cover the content the moment we background so nothing sensitive is in the last-rendered frame
        // (recents snapshot) or briefly visible on the next resume before the re-lock decision lands.
        if (appLockEnabledCached && _uiState.value == AppLockUiState.Unlocked) {
            _uiState.value = AppLockUiState.Checking
        }
    }

    /** Called by the host after a successful `BiometricPrompt`. */
    fun onAuthenticated() {
        viewModelScope.launch {
            when (withContext(defaultDispatcher) { unlockOrArm() }) {
                UnlockResult.OK -> {
                    AppLog.i(TAG, "auth seal unlocked; cache readable")
                    gate.onAuthenticated()
                    publish()
                }

                UnlockResult.UNRECOVERABLE -> {
                    // The passphrase is permanently unrecoverable (key invalidated or deleted by a
                    // screen-lock change). Wipe the cache safely at the next cold start and re-sync.
                    AppLog.w(TAG, "encrypted cache passphrase unrecoverable; clearing cache")
                    clearCacheAndRestart(disableAppLock = false)
                }

                UnlockResult.RETRY -> {
                    gate.lock()
                    publish(context.getString(R.string.app_lock_unlock_failed))
                }
            }
        }
    }

    /** Called by the host when the prompt is cancelled or errors. */
    fun onAuthError(message: String?) {
        gate.lock()
        publish(message)
    }

    /** Outcome of unwrapping/arming the auth-bound passphrase after a successful device auth. */
    private enum class UnlockResult { OK, UNRECOVERABLE, RETRY }

    /**
     * Reconcile the auth-bound passphrase with the encrypted cache after a successful device auth:
     *  - warm re-auth (session already holds the passphrase, DB already open): nothing to do;
     *  - a sealed passphrase exists: unwrap it into the session — this covers the normal encrypted
     *    cache AND the transitional case where encryptCache was just turned off but the on-disk DB is
     *    still encrypted and `provideDatabase` needs the passphrase to decrypt it to plaintext;
     *  - no seal yet AND encryptCache is on: first-time arm within this valid auth window;
     *  - no seal and encryptCache off: app-lock is a pure UI gate this session — nothing to unlock.
     *
     * Failures are classified so we only wipe the cache when the passphrase is truly lost: a
     * permanently-invalidated OR deleted key is [UNRECOVERABLE]; a transient auth error (e.g. the
     * short key-validity window elapsed) is a [RETRY].
     */
    private suspend fun unlockOrArm(): UnlockResult {
        if (session.isUnlocked()) return UnlockResult.OK
        if (databaseKeyStore.hasAuthSealedPassphrase()) return unwrapSealedPassphrase()
        // No auth seal yet: arm one only when there is (or will be) an encrypted cache to protect.
        if (!settingsRepository.settings.first().encryptCache) return UnlockResult.OK
        return runCatching { databaseKeyStore.sealWithAuth() }.fold(
            onSuccess = { UnlockResult.OK },
            onFailure = { e ->
                AppLog.w(TAG, "arming auth seal failed", e)
                UnlockResult.RETRY
            },
        )
    }

    private suspend fun unwrapSealedPassphrase(): UnlockResult {
        // A sealed passphrase exists but its key is gone entirely: it can never be unwrapped.
        if (!databaseKeyCipher.hasKey()) {
            AppLog.w(TAG, "auth-sealed passphrase present but key was deleted; cache unrecoverable")
            return UnlockResult.UNRECOVERABLE
        }
        return try {
            databaseKeyStore.unlockWithAuth()
            UnlockResult.OK
        } catch (e: KeyPermanentlyInvalidatedException) {
            AppLog.w(TAG, "auth-bound key permanently invalidated", e)
            UnlockResult.UNRECOVERABLE
        } catch (e: UserNotAuthenticatedException) {
            AppLog.w(TAG, "auth window elapsed before unwrap; will retry", e)
            UnlockResult.RETRY
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Any other failure (corrupt sealed blob, OEM keymaster error, etc.) must NOT crash the
            // process right after a successful auth. Re-lock and let the user retry rather than wiping
            // the cache on an ambiguous error (a genuinely lost key still surfaces as UNRECOVERABLE
            // via hasKey()/KeyPermanentlyInvalidatedException above).
            AppLog.w(TAG, "unexpected failure unwrapping auth-sealed passphrase; will retry", e)
            UnlockResult.RETRY
        }
    }

    private suspend fun clearCacheAndRestart(disableAppLock: Boolean) {
        AppLog.w(TAG, "clearing encrypted cache and restarting (disableAppLock=$disableAppLock)")
        withContext(defaultDispatcher) {
            // Record the wipe intent BEFORE flipping app-lock off, so a crash between the two writes
            // leaves the wipe still pending (recoverable) rather than a disabled gate over a stale key.
            // Both are DataStore edits that only return once durably committed, so they survive the
            // restart below without further ceremony.
            databaseKeyStore.setClearPending()
            if (disableAppLock) settingsRepository.setAppLock(false)
            // Enqueue the post-wipe re-sync and BLOCK until WorkManager has durably persisted its
            // WorkSpec before we hand off to the restart. syncNow() only *schedules* the insert on
            // WorkManager's serial task executor; killing the process (via restartProcess) can race
            // that async insert and drop the re-sync, leaving an empty mailbox after the wipe until the
            // next periodic sync. Awaiting the enqueue Operation makes "survives the restart" real.
            awaitSyncEnqueue(syncScheduler.syncNow())
        }
        restartProcess()
    }

    /**
     * Block until WorkManager confirms the re-sync WorkSpec is durably persisted, bounded by
     * [SYNC_ENQUEUE_TIMEOUT_SECONDS] so a stuck insert can never wedge recovery. A timeout/failure is
     * logged and we restart anyway: the periodic sync will still eventually refill the wiped cache, so
     * a best-effort wait is strictly better than the previous fire-and-forget enqueue. Runs on
     * [defaultDispatcher] (never the main thread) because [Operation.result]'s get blocks.
     */
    private fun awaitSyncEnqueue(operation: Operation) {
        try {
            operation.result.get(SYNC_ENQUEUE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            AppLog.w(TAG, "re-sync enqueue not confirmed within timeout; restarting anyway", e)
        } catch (e: ExecutionException) {
            AppLog.w(TAG, "re-sync enqueue failed; restarting anyway", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            AppLog.w(TAG, "interrupted awaiting re-sync enqueue; restarting anyway", e)
        }
    }

    /**
     * Relaunch the app in a fresh process so [org.libremail.di.DatabaseModule] wipes the cache before
     * Room reopens it. Delegates to [ProcessRestarter], which issues the relaunch from a separate
     * process that survives this one being killed — a same-process "startActivity then exit(0)" is
     * unreliable because ActivityManager may schedule the relaunch into the dying process and drop it.
     * DEVICE-ONLY end to end: the multi-process kill/relaunch cannot be exercised in JVM unit tests.
     */
    private fun restartProcess() {
        processRestarter.restart()
    }

    // Monotonic clock so a wall-clock change can't extend the inactivity grace window.
    private fun now(): Long = SystemClock.elapsedRealtime()

    private companion object {
        const val TAG = "LibreMailAppLock"

        // Upper bound on waiting for WorkManager to persist the re-sync WorkSpec. The insert is
        // normally sub-second; this only caps a pathological stall so recovery can't hang before the
        // restart. On timeout we restart anyway (the periodic sync still refills the cache later).
        const val SYNC_ENQUEUE_TIMEOUT_SECONDS = 5L
    }
}
