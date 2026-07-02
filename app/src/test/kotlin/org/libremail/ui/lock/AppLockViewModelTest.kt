// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.lock

import android.os.SystemClock
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.data.security.AppLockGate
import org.libremail.data.settings.AppSettings
import org.libremail.data.settings.SettingsRepository
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Wiring guard for #101: the app-lock gate must be an INJECTED, application-scoped dependency so its
 * grace window survives Activity recreation — NOT a field the Activity-scoped ViewModel constructs
 * itself (which Back on the task root would drop on API 29/30). These tests exercise the synchronous
 * paths that delegate to the injected gate; the grace math itself is covered exhaustively — and
 * deterministically — by AppLockGateTest. Broader ViewModel coverage is issue #100.
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

    private fun viewModel(gate: AppLockGate, appLock: Boolean = true): AppLockViewModel {
        val settings = mockk<SettingsRepository>()
        every { settings.settings } returns flowOf(AppSettings(appLock = appLock))
        return AppLockViewModel(
            context = mockk(relaxed = true),
            settingsRepository = settings,
            appLockManager = mockk(relaxed = true),
            databaseKeyStore = mockk(relaxed = true),
            databaseKeyCipher = mockk(relaxed = true),
            session = mockk(relaxed = true),
            syncScheduler = mockk(relaxed = true),
            gate = gate,
        )
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
}
