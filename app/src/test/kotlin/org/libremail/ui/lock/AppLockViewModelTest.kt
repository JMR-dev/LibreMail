// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.lock

import android.os.SystemClock
import android.util.Log
import androidx.work.Operation
import com.google.common.util.concurrent.ListenableFuture
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
import org.libremail.data.security.DatabaseKeyStore
import org.libremail.data.security.KeyInvalidationPolicy
import org.libremail.data.security.LockAction
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
 * deterministically — by AppLockGateTest. Broader ViewModel coverage is issue #100.
 *
 * The recovery-restart tests (#99) pin the ordering that makes the key-invalidation "clear + re-sync"
 * safe: the re-sync enqueue must be durably persisted (its WorkManager Operation awaited) BEFORE the
 * process is restarted, and a stuck enqueue must never wedge recovery. The separate-process relaunch
 * itself is device-only; here we assert the ViewModel's orchestration around ProcessRestarter.
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
    ): AppLockViewModel {
        every { settingsRepository.settings } returns flowOf(AppSettings(appLock = appLock))
        return AppLockViewModel(
            context = mockk(relaxed = true),
            settingsRepository = settingsRepository,
            appLockManager = mockk(relaxed = true),
            databaseKeyStore = databaseKeyStore,
            databaseKeyCipher = mockk(relaxed = true),
            session = mockk(relaxed = true),
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
}
