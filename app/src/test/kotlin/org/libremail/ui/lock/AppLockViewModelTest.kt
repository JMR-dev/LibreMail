// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.lock

import android.content.Context
import android.os.SystemClock
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import android.util.Log
import androidx.work.Operation
import com.google.common.util.concurrent.ListenableFuture
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.data.security.AppLockGate
import org.libremail.data.security.AppLockManager
import org.libremail.data.security.DatabaseKeyCipher
import org.libremail.data.security.DatabaseKeyStore
import org.libremail.data.security.KeyInvalidationPolicy
import org.libremail.data.security.LockAction
import org.libremail.data.security.LockState
import org.libremail.data.security.PassphraseSession
import org.libremail.data.settings.AppSettings
import org.libremail.data.settings.SettingsRepository
import org.libremail.data.sync.SyncScheduler
import org.libremail.restart.ProcessRestarter
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Wiring guard for #101: the app-lock gate must be an INJECTED, application-scoped dependency so its
 * grace window survives Activity recreation — NOT a field the Activity-scoped ViewModel constructs
 * itself (which Back on the task root would drop on API 29/30). These tests exercise the synchronous
 * paths that delegate to the injected gate; the grace math itself is covered exhaustively — and
 * deterministically — by AppLockGateTest.
 *
 * The recovery-restart tests pin the ordering that makes the key-invalidation "clear + re-sync" safe:
 * the re-sync enqueue must be durably persisted (its WorkManager Operation awaited) BEFORE the process
 * is restarted, and a stuck enqueue must never wedge recovery. The separate-process relaunch itself is
 * device-only; here we assert the ViewModel's orchestration around ProcessRestarter.
 *
 * Issue #100 broadens this to the ViewModel's security-critical branching: the `onForeground`
 * [LockAction] dispatch (that DISABLE_APP_LOCK persists the setting, CLEAR_* set the pending flag and
 * restart, and CLEAR_AND_REQUIRE_AUTH keeps app-lock on) and the `onAuthenticated` unlock/arm
 * classification (OK / UNRECOVERABLE / RETRY), so a mutation that wipes user data or drops the lock is
 * caught here rather than only on a device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLockViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun viewModel(
        gate: AppLockGate,
        appLock: Boolean = true,
        settingsRepository: SettingsRepository = mockk(relaxed = true),
        databaseKeyStore: DatabaseKeyStore = mockk(relaxed = true),
        syncScheduler: SyncScheduler = mockk(relaxed = true),
        processRestarter: ProcessRestarter = mockk(relaxed = true),
        encryptCache: Boolean = false,
        databaseKeyCipher: DatabaseKeyCipher = mockk(relaxed = true),
        session: PassphraseSession = mockk(relaxed = true),
        appLockManager: AppLockManager = mockk(relaxed = true),
        context: Context = mockk(relaxed = true),
    ): AppLockViewModel {
        val appSettings = AppSettings(appLock = appLock, encryptCache = encryptCache)
        every { settingsRepository.settings } returns flowOf(appSettings)
        return AppLockViewModel(
            context = context,
            settingsRepository = settingsRepository,
            appLockManager = appLockManager,
            databaseKeyStore = databaseKeyStore,
            databaseKeyCipher = databaseKeyCipher,
            session = session,
            syncScheduler = syncScheduler,
            processRestarter = processRestarter,
            gate = gate,
            // Run the off-main recovery work on the test scheduler so its ordering is deterministic.
        ).also { it.defaultDispatcher = dispatcher }
    }

    @Test
    fun `onBackground records the background on the injected gate`() = runTest(dispatcher) {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 5_000L
        val gate = mockk<AppLockGate>(relaxed = true)

        viewModel(gate).onBackground()

        // Delegating to the gate passed into the constructor (an injected, shareable singleton) rather
        // than an internally-constructed one is the whole point of the #101 hoist: the same instance
        // must survive Activity recreation so the grace marker persists.
        verify { gate.onBackground(5_000L) }
    }

    @Test
    fun `onAuthError re-locks through the injected gate and surfaces the error`() = runTest(dispatcher) {
        val gate = mockk<AppLockGate>(relaxed = true)
        val vm = viewModel(gate)

        vm.onAuthError("boom")

        verify { gate.lock() }
        val state = assertIs<AppLockUiState.Locked>(vm.uiState.value)
        assertEquals("boom", state.error)
    }

    @Test
    fun `recovery persists the re-sync enqueue before restarting`() = runTest(dispatcher) {
        val (future, syncScheduler) = enqueueingScheduler()
        val databaseKeyStore = mockk<DatabaseKeyStore>(relaxed = true)
        val processRestarter = mockk<ProcessRestarter>(relaxed = true)
        val vm = clearOnForegroundViewModel(
            databaseKeyStore = databaseKeyStore,
            syncScheduler = syncScheduler,
            processRestarter = processRestarter,
        )

        vm.onForeground()
        advanceUntilIdle()

        // The re-sync WorkSpec must be durably persisted (the enqueue Operation awaited) BEFORE the
        // process is torn down. Otherwise WorkManager's async insert races the process death, the
        // enqueue is lost, and the just-wiped cache never refills until the next periodic sync.
        coVerifyOrder {
            databaseKeyStore.setClearPending()
            syncScheduler.syncNow()
            future.get(any(), any())
            processRestarter.restart()
        }
    }

    @Test
    fun `recovery still restarts when the re-sync enqueue await times out`() = runTest(dispatcher) {
        val (future, syncScheduler) = enqueueingScheduler()
        every { future.get(any(), any()) } throws TimeoutException("stuck insert")
        val processRestarter = mockk<ProcessRestarter>(relaxed = true)
        val vm = clearOnForegroundViewModel(syncScheduler = syncScheduler, processRestarter = processRestarter)

        vm.onForeground()
        advanceUntilIdle()

        // A stuck WorkManager insert must not wedge recovery: the timeout is swallowed and we restart
        // anyway (the periodic sync will still refill the wiped cache later).
        verify { future.get(any(), any()) }
        verify { processRestarter.restart() }
    }

    // --- onForeground: LockAction dispatch (issue #100) ------------------------------------------

    @Test
    fun `onForeground with app-lock off shows the app`() = runTest(dispatcher) {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns FOREGROUND_AT
        val vm = viewModel(gate = mockk(relaxed = true), appLock = false)

        vm.onForeground()
        advanceUntilIdle()

        assertIs<AppLockUiState.Unlocked>(vm.uiState.value)
    }

    @Test
    fun `onForeground DISABLE_APP_LOCK persists app-lock off and shows the app`() = runTest(dispatcher) {
        val settingsRepository = mockk<SettingsRepository>(relaxed = true)
        val processRestarter = mockk<ProcessRestarter>(relaxed = true)
        val vm = foregroundResolving(
            LockAction.DISABLE_APP_LOCK,
            settingsRepository = settingsRepository,
            processRestarter = processRestarter,
        )

        vm.onForeground()
        advanceUntilIdle()

        // The lock was silently dropped (device no longer secure, nothing encrypted to lose): the
        // setting is persisted off and the app is shown, with no cache wipe / restart.
        coVerify { settingsRepository.setAppLock(false) }
        assertIs<AppLockUiState.Unlocked>(vm.uiState.value)
        verify(exactly = 0) { processRestarter.restart() }
    }

    @Test
    fun `onForeground CLEAR_AND_DISABLE clears, disables app-lock, then restarts in order`() = runTest(dispatcher) {
        val (future, syncScheduler) = enqueueingScheduler()
        val settingsRepository = mockk<SettingsRepository>(relaxed = true)
        val databaseKeyStore = mockk<DatabaseKeyStore>(relaxed = true)
        val processRestarter = mockk<ProcessRestarter>(relaxed = true)
        val vm = foregroundResolving(
            LockAction.CLEAR_AND_DISABLE,
            settingsRepository = settingsRepository,
            databaseKeyStore = databaseKeyStore,
            syncScheduler = syncScheduler,
            processRestarter = processRestarter,
        )

        vm.onForeground()
        advanceUntilIdle()

        // Crash-safe recovery order: record the wipe intent and drop the gate BEFORE the durable
        // re-sync enqueue is awaited and the process is torn down.
        coVerifyOrder {
            databaseKeyStore.setClearPending()
            settingsRepository.setAppLock(false)
            syncScheduler.syncNow()
            future.get(any(), any())
            processRestarter.restart()
        }
    }

    @Test
    fun `onForeground CLEAR_AND_REQUIRE_AUTH clears and restarts but keeps app-lock on`() = runTest(dispatcher) {
        val (future, syncScheduler) = enqueueingScheduler()
        val settingsRepository = mockk<SettingsRepository>(relaxed = true)
        val databaseKeyStore = mockk<DatabaseKeyStore>(relaxed = true)
        val processRestarter = mockk<ProcessRestarter>(relaxed = true)
        val vm = foregroundResolving(
            LockAction.CLEAR_AND_REQUIRE_AUTH,
            settingsRepository = settingsRepository,
            databaseKeyStore = databaseKeyStore,
            syncScheduler = syncScheduler,
            processRestarter = processRestarter,
        )

        vm.onForeground()
        advanceUntilIdle()

        // Same clear + durable re-sync + restart, but app-lock stays ON: the wipe re-arms a fresh key
        // on the next authentication, so setAppLock(false) must NOT be called.
        coVerifyOrder {
            databaseKeyStore.setClearPending()
            future.get(any(), any())
            processRestarter.restart()
        }
        coVerify(exactly = 0) { settingsRepository.setAppLock(false) }
    }

    @Test
    fun `onForeground PROCEED shows the app without restarting`() = runTest(dispatcher) {
        val processRestarter = mockk<ProcessRestarter>(relaxed = true)
        val vm = foregroundResolving(LockAction.PROCEED, processRestarter = processRestarter)

        vm.onForeground()
        advanceUntilIdle()

        assertIs<AppLockUiState.Unlocked>(vm.uiState.value)
        verify(exactly = 0) { processRestarter.restart() }
    }

    @Test
    fun `onForeground REQUIRE_AUTH advances the gate and publishes its locked decision`() = runTest(dispatcher) {
        val gate = mockk<AppLockGate>(relaxed = true)
        every { gate.state } returns LockState.LOCKED
        val vm = foregroundResolving(LockAction.REQUIRE_AUTH, gate = gate)

        vm.onForeground()
        advanceUntilIdle()

        verify { gate.onForeground(FOREGROUND_AT, appLockEnabled = true) }
        assertIs<AppLockUiState.Locked>(vm.uiState.value)
    }

    // --- onAuthenticated: unlockOrArm / unwrapSealedPassphrase classification (issue #100) --------

    @Test
    fun `onAuthenticated with an already-unlocked session unlocks the gate without unwrapping`() = runTest(dispatcher) {
        val gate = mockk<AppLockGate>(relaxed = true)
        every { gate.state } returns LockState.UNLOCKED
        val session = mockk<PassphraseSession>(relaxed = true)
        every { session.isUnlocked() } returns true
        val databaseKeyStore = mockk<DatabaseKeyStore>(relaxed = true)
        val vm = viewModel(gate = gate, session = session, databaseKeyStore = databaseKeyStore)

        vm.onAuthenticated()
        advanceUntilIdle()

        verify { gate.onAuthenticated() }
        assertIs<AppLockUiState.Unlocked>(vm.uiState.value)
        coVerify(exactly = 0) { databaseKeyStore.unlockWithAuth() }
        coVerify(exactly = 0) { databaseKeyStore.sealWithAuth() }
    }

    @Test
    fun `onAuthenticated unwraps a sealed passphrase and unlocks the gate`() = runTest(dispatcher) {
        val gate = mockk<AppLockGate>(relaxed = true)
        every { gate.state } returns LockState.UNLOCKED
        val session = mockk<PassphraseSession>(relaxed = true)
        every { session.isUnlocked() } returns false
        val databaseKeyStore = mockk<DatabaseKeyStore>(relaxed = true)
        coEvery { databaseKeyStore.hasAuthSealedPassphrase() } returns true
        val databaseKeyCipher = mockk<DatabaseKeyCipher>(relaxed = true)
        every { databaseKeyCipher.hasKey() } returns true
        val vm = viewModel(
            gate = gate,
            session = session,
            databaseKeyStore = databaseKeyStore,
            databaseKeyCipher = databaseKeyCipher,
        )

        vm.onAuthenticated()
        advanceUntilIdle()

        coVerify { databaseKeyStore.unlockWithAuth() }
        verify { gate.onAuthenticated() }
        assertIs<AppLockUiState.Unlocked>(vm.uiState.value)
    }

    @Test
    fun `onAuthenticated clears the cache when the auth-bound key was deleted`() = runTest(dispatcher) {
        stubLog()
        val (future, syncScheduler) = enqueueingScheduler()
        val session = mockk<PassphraseSession>(relaxed = true)
        every { session.isUnlocked() } returns false
        val databaseKeyStore = mockk<DatabaseKeyStore>(relaxed = true)
        coEvery { databaseKeyStore.hasAuthSealedPassphrase() } returns true
        val databaseKeyCipher = mockk<DatabaseKeyCipher>(relaxed = true)
        every { databaseKeyCipher.hasKey() } returns false // key gone entirely -> unrecoverable
        val processRestarter = mockk<ProcessRestarter>(relaxed = true)
        val vm = viewModel(
            gate = mockk(relaxed = true),
            session = session,
            databaseKeyStore = databaseKeyStore,
            databaseKeyCipher = databaseKeyCipher,
            syncScheduler = syncScheduler,
            processRestarter = processRestarter,
        )

        vm.onAuthenticated()
        advanceUntilIdle()

        // Unrecoverable: never attempt the unwrap; wipe the cache and restart (app-lock stays on).
        coVerify(exactly = 0) { databaseKeyStore.unlockWithAuth() }
        coVerifyOrder {
            databaseKeyStore.setClearPending()
            future.get(any(), any())
            processRestarter.restart()
        }
    }

    @Test
    fun `onAuthenticated clears the cache when the auth-bound key was permanently invalidated`() = runTest(dispatcher) {
        stubLog()
        val (_, syncScheduler) = enqueueingScheduler()
        val session = mockk<PassphraseSession>(relaxed = true)
        every { session.isUnlocked() } returns false
        val databaseKeyStore = mockk<DatabaseKeyStore>(relaxed = true)
        coEvery { databaseKeyStore.hasAuthSealedPassphrase() } returns true
        coEvery { databaseKeyStore.unlockWithAuth() } throws mockk<KeyPermanentlyInvalidatedException>(relaxed = true)
        val databaseKeyCipher = mockk<DatabaseKeyCipher>(relaxed = true)
        every { databaseKeyCipher.hasKey() } returns true
        val processRestarter = mockk<ProcessRestarter>(relaxed = true)
        val vm = viewModel(
            gate = mockk(relaxed = true),
            session = session,
            databaseKeyStore = databaseKeyStore,
            databaseKeyCipher = databaseKeyCipher,
            syncScheduler = syncScheduler,
            processRestarter = processRestarter,
        )

        vm.onAuthenticated()
        advanceUntilIdle()

        coVerify { databaseKeyStore.setClearPending() }
        verify { processRestarter.restart() }
    }

    @Test
    fun `onAuthenticated re-locks for a retry when the auth window elapsed`() = runTest(dispatcher) {
        stubLog()
        val gate = mockk<AppLockGate>(relaxed = true)
        every { gate.state } returns LockState.LOCKED
        val session = mockk<PassphraseSession>(relaxed = true)
        every { session.isUnlocked() } returns false
        val databaseKeyStore = mockk<DatabaseKeyStore>(relaxed = true)
        coEvery { databaseKeyStore.hasAuthSealedPassphrase() } returns true
        coEvery { databaseKeyStore.unlockWithAuth() } throws mockk<UserNotAuthenticatedException>(relaxed = true)
        val databaseKeyCipher = mockk<DatabaseKeyCipher>(relaxed = true)
        every { databaseKeyCipher.hasKey() } returns true
        val processRestarter = mockk<ProcessRestarter>(relaxed = true)
        val vm = viewModel(
            gate = gate,
            session = session,
            databaseKeyStore = databaseKeyStore,
            databaseKeyCipher = databaseKeyCipher,
            processRestarter = processRestarter,
        )

        vm.onAuthenticated()
        advanceUntilIdle()

        // A lapsed auth window is transient: re-lock and let the user retry — never wipe the cache.
        verify { gate.lock() }
        assertIs<AppLockUiState.Locked>(vm.uiState.value)
        verify(exactly = 0) { processRestarter.restart() }
    }

    @Test
    fun `onAuthenticated re-locks for a retry on an ambiguous unwrap failure`() = runTest(dispatcher) {
        stubLog()
        val gate = mockk<AppLockGate>(relaxed = true)
        every { gate.state } returns LockState.LOCKED
        val session = mockk<PassphraseSession>(relaxed = true)
        every { session.isUnlocked() } returns false
        val databaseKeyStore = mockk<DatabaseKeyStore>(relaxed = true)
        coEvery { databaseKeyStore.hasAuthSealedPassphrase() } returns true
        coEvery { databaseKeyStore.unlockWithAuth() } throws IllegalStateException("corrupt sealed blob")
        val databaseKeyCipher = mockk<DatabaseKeyCipher>(relaxed = true)
        every { databaseKeyCipher.hasKey() } returns true
        val processRestarter = mockk<ProcessRestarter>(relaxed = true)
        val vm = viewModel(
            gate = gate,
            session = session,
            databaseKeyStore = databaseKeyStore,
            databaseKeyCipher = databaseKeyCipher,
            processRestarter = processRestarter,
        )

        vm.onAuthenticated()
        advanceUntilIdle()

        // An ambiguous error must NOT wipe the cache on an otherwise-successful auth: re-lock + retry.
        verify { gate.lock() }
        verify(exactly = 0) { processRestarter.restart() }
    }

    @Test
    fun `onAuthenticated with no seal and encryption off unlocks without arming`() = runTest(dispatcher) {
        val gate = mockk<AppLockGate>(relaxed = true)
        every { gate.state } returns LockState.UNLOCKED
        val session = mockk<PassphraseSession>(relaxed = true)
        every { session.isUnlocked() } returns false
        val databaseKeyStore = mockk<DatabaseKeyStore>(relaxed = true)
        coEvery { databaseKeyStore.hasAuthSealedPassphrase() } returns false
        val vm = viewModel(
            gate = gate,
            session = session,
            databaseKeyStore = databaseKeyStore,
            encryptCache = false,
        )

        vm.onAuthenticated()
        advanceUntilIdle()

        // App-lock is a pure UI gate this session: there is no encrypted cache to arm.
        coVerify(exactly = 0) { databaseKeyStore.sealWithAuth() }
        verify { gate.onAuthenticated() }
        assertIs<AppLockUiState.Unlocked>(vm.uiState.value)
    }

    @Test
    fun `onAuthenticated arms a fresh auth seal when encryption is on and none exists`() = runTest(dispatcher) {
        val gate = mockk<AppLockGate>(relaxed = true)
        every { gate.state } returns LockState.UNLOCKED
        val session = mockk<PassphraseSession>(relaxed = true)
        every { session.isUnlocked() } returns false
        val databaseKeyStore = mockk<DatabaseKeyStore>(relaxed = true)
        coEvery { databaseKeyStore.hasAuthSealedPassphrase() } returns false
        val vm = viewModel(
            gate = gate,
            session = session,
            databaseKeyStore = databaseKeyStore,
            encryptCache = true,
        )

        vm.onAuthenticated()
        advanceUntilIdle()

        coVerify { databaseKeyStore.sealWithAuth() }
        verify { gate.onAuthenticated() }
        assertIs<AppLockUiState.Unlocked>(vm.uiState.value)
    }

    @Test
    fun `onAuthenticated re-locks for a retry when arming the auth seal fails`() = runTest(dispatcher) {
        stubLog()
        val gate = mockk<AppLockGate>(relaxed = true)
        every { gate.state } returns LockState.LOCKED
        val session = mockk<PassphraseSession>(relaxed = true)
        every { session.isUnlocked() } returns false
        val databaseKeyStore = mockk<DatabaseKeyStore>(relaxed = true)
        coEvery { databaseKeyStore.hasAuthSealedPassphrase() } returns false
        coEvery { databaseKeyStore.sealWithAuth() } throws IllegalStateException("keystore busy")
        val processRestarter = mockk<ProcessRestarter>(relaxed = true)
        val vm = viewModel(
            gate = gate,
            session = session,
            databaseKeyStore = databaseKeyStore,
            encryptCache = true,
            processRestarter = processRestarter,
        )

        vm.onAuthenticated()
        advanceUntilIdle()

        verify { gate.lock() }
        assertIs<AppLockUiState.Locked>(vm.uiState.value)
        verify(exactly = 0) { processRestarter.restart() }
    }

    /** A [SyncScheduler] whose `syncNow()` returns an [Operation] whose result future can be stubbed. */
    private fun enqueueingScheduler(): Pair<ListenableFuture<Operation.State.SUCCESS>, SyncScheduler> {
        val future = mockk<ListenableFuture<Operation.State.SUCCESS>>()
        every { future.get(any(), any()) } returns Operation.SUCCESS
        val operation = mockk<Operation> { every { result } returns future }
        val syncScheduler = mockk<SyncScheduler> { every { syncNow() } returns operation }
        return future to syncScheduler
    }

    /**
     * A ViewModel whose next `onForeground()` resolves to a cache-clear + restart: the pure decision
     * table is stubbed to CLEAR_AND_REQUIRE_AUTH so the test drives the recovery path deterministically
     * without reproducing the full key-invalidation device state (that logic is KeyInvalidationPolicyTest).
     */
    private fun clearOnForegroundViewModel(
        databaseKeyStore: DatabaseKeyStore = mockk(relaxed = true),
        syncScheduler: SyncScheduler = mockk(relaxed = true),
        processRestarter: ProcessRestarter = mockk(relaxed = true),
    ): AppLockViewModel {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 1_000L
        // android.util.Log is a no-op stub that throws "not mocked" in JVM tests; the timeout path logs.
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        mockkObject(KeyInvalidationPolicy)
        every { KeyInvalidationPolicy.decide(any(), any(), any(), any()) } returns LockAction.CLEAR_AND_REQUIRE_AUTH
        return viewModel(
            gate = mockk(relaxed = true),
            databaseKeyStore = databaseKeyStore,
            syncScheduler = syncScheduler,
            processRestarter = processRestarter,
        )
    }

    /**
     * Builds a ViewModel whose next `onForeground()` resolves to [action] by stubbing the pure decision
     * table directly (its own exhaustive coverage is KeyInvalidationPolicyTest) plus the Android statics
     * `onForeground` touches, so each [LockAction] branch is driven without reproducing device state.
     */
    private fun foregroundResolving(
        action: LockAction,
        gate: AppLockGate = mockk(relaxed = true),
        settingsRepository: SettingsRepository = mockk(relaxed = true),
        databaseKeyStore: DatabaseKeyStore = mockk(relaxed = true),
        syncScheduler: SyncScheduler = mockk(relaxed = true),
        processRestarter: ProcessRestarter = mockk(relaxed = true),
    ): AppLockViewModel {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns FOREGROUND_AT
        stubLog()
        mockkObject(KeyInvalidationPolicy)
        every { KeyInvalidationPolicy.decide(any(), any(), any(), any()) } returns action
        return viewModel(
            gate = gate,
            settingsRepository = settingsRepository,
            databaseKeyStore = databaseKeyStore,
            syncScheduler = syncScheduler,
            processRestarter = processRestarter,
        )
    }

    /** android.util.Log is a no-op stub that throws "not mocked" in JVM tests; the recovery paths log. */
    private fun stubLog() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
    }

    private companion object {
        const val FOREGROUND_AT = 1_000L
    }
}
