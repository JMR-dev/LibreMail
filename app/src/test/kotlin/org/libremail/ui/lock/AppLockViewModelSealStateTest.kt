// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.lock

import android.content.Context
import android.os.SystemClock
import androidx.work.Operation
import com.google.common.util.concurrent.ListenableFuture
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
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
import org.libremail.data.security.LockState
import org.libremail.data.security.PassphraseSession
import org.libremail.data.settings.AppSettings
import org.libremail.data.settings.SettingsRepository
import org.libremail.data.sync.SyncScheduler
import org.libremail.reporting.AppLog
import org.libremail.reporting.RingLogBuffer
import org.libremail.restart.ProcessRestarter
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The issue-#479 seal-present/setting-off matrix: `onForeground` must derive
 * [org.libremail.data.security.KeyInvalidationPolicy]'s `encryptedCacheProtected` input from the
 * ACTUAL protection state (`encryptCache setting || hasAuthSealedPassphrase()`), never the setting
 * alone. The setting flips immediately, but the on-disk decrypt runs only at the next cold start, so
 * an auth-sealed (still encrypted) cache can outlive `encryptCache == false` — the transitional
 * window in which the old raw-setting input silently chose DISABLE_APP_LOCK on device-lock removal,
 * orphaned SEALED_AUTH without a wipe, and bricked every later launch inside
 * `DatabaseKeyStore.resolvePassphrase` (a `session.await()` nothing could ever complete).
 *
 * Unlike `AppLockViewModelTest`'s LockAction-dispatch tests (which stub the policy object), these
 * drive the REAL [org.libremail.data.security.KeyInvalidationPolicy] through the ViewModel so the
 * derivation itself — not just the dispatch — is pinned. Split into its own class (rather than grown
 * onto AppLockViewModelTest) to respect detekt's LargeClass budget there.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLockViewModelSealStateTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val logBuffer = RingLogBuffer()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        // android.util.Log is a throwing stub under plain JVM tests and AppLog always forwards to
        // it; stub the levels these paths breadcrumb through, then install a real buffer so the
        // breadcrumbs are asserted for real (never `verify { Log... }`) — mirroring AppLockViewModelTest.
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>(), any()) } returns 0
        AppLog.install(logBuffer)
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns FOREGROUND_AT
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `lock removal during the encryptCache-off transitional window clears and disables`() = runTest(dispatcher) {
        val fixture = fixture(deviceSecure = false, authSealed = true)

        fixture.vm.onForeground()
        advanceUntilIdle()

        // The setting is off but SEALED_AUTH still exists, so the policy must land on
        // CLEAR_AND_DISABLE: wipe scheduled, gate dropped, process restarted — never the old
        // DISABLE_APP_LOCK, which orphaned the seal without a wipe and hung every later launch
        // forever inside DatabaseKeyStore.resolvePassphrase (the app bricked behind the cache gate).
        coVerifyOrder {
            fixture.databaseKeyStore.setClearPending()
            fixture.settingsRepository.setAppLock(false)
            fixture.processRestarter.restart()
        }
        // Issue #479: the seal-overrides-setting derivation and the decision are both breadcrumbed.
        val messages = logBuffer.snapshot().map { it.message }
        assertTrue("encryptCache off but passphrase still auth-sealed; treating cache as protected" in messages)
        assertTrue("app-lock foreground decision: CLEAR_AND_DISABLE" in messages)
    }

    @Test
    fun `lock removal with no auth seal and the setting off disables without clearing`() = runTest(dispatcher) {
        val fixture = fixture(deviceSecure = false, authSealed = false)

        fixture.vm.onForeground()
        advanceUntilIdle()

        // Nothing encrypted and nothing sealed: still DISABLE_APP_LOCK — the seal-derived input
        // must not turn every lock removal into a destructive wipe.
        coVerify { fixture.settingsRepository.setAppLock(false) }
        coVerify(exactly = 0) { fixture.databaseKeyStore.setClearPending() }
        verify(exactly = 0) { fixture.processRestarter.restart() }
        assertIs<AppLockUiState.Unlocked>(fixture.vm.uiState.value)
    }

    @Test
    fun `key invalidation while still auth-sealed with the setting off clears and keeps the lock`() =
        runTest(dispatcher) {
            val fixture = fixture(deviceSecure = true, authSealed = true, keyInvalidated = true)

            fixture.vm.onForeground()
            advanceUntilIdle()

            // Biometric re-enrollment during the transitional window: the auth-sealed cache is
            // unrecoverable, so it is cleared and app-lock stays ON (CLEAR_AND_REQUIRE_AUTH).
            coVerify { fixture.databaseKeyStore.setClearPending() }
            verify { fixture.processRestarter.restart() }
            coVerify(exactly = 0) { fixture.settingsRepository.setAppLock(false) }
        }

    @Test
    fun `a healthy device still just requires auth during the transitional window`() = runTest(dispatcher) {
        val gate = mockk<AppLockGate>(relaxed = true)
        every { gate.state } returns LockState.LOCKED
        val fixture = fixture(deviceSecure = true, authSealed = true, gate = gate)

        fixture.vm.onForeground()
        advanceUntilIdle()

        // Secure device + valid key: the seal-derived input must not disturb the healthy row —
        // REQUIRE_AUTH, no wipe, no restart (the seal is unwrapped after the next auth as before).
        verify { gate.onForeground(FOREGROUND_AT, appLockEnabled = true) }
        assertIs<AppLockUiState.Locked>(fixture.vm.uiState.value)
        coVerify(exactly = 0) { fixture.databaseKeyStore.setClearPending() }
        verify(exactly = 0) { fixture.processRestarter.restart() }
    }

    /**
     * A ViewModel over the REAL KeyInvalidationPolicy with app-lock ON and encryptCache OFF (the
     * transitional window's setting state), plus mocks pinned to the given device/seal/key state.
     */
    private class Fixture(
        val vm: AppLockViewModel,
        val settingsRepository: SettingsRepository,
        val databaseKeyStore: DatabaseKeyStore,
        val processRestarter: ProcessRestarter,
    )

    private fun fixture(
        deviceSecure: Boolean,
        authSealed: Boolean,
        keyInvalidated: Boolean = false,
        gate: AppLockGate = mockk(relaxed = true),
    ): Fixture {
        val settingsRepository = mockk<SettingsRepository>(relaxed = true)
        every { settingsRepository.settings } returns flowOf(AppSettings(appLock = true, encryptCache = false))
        val databaseKeyStore = mockk<DatabaseKeyStore>(relaxed = true)
        coEvery { databaseKeyStore.hasAuthSealedPassphrase() } returns authSealed
        val appLockManager = mockk<AppLockManager>(relaxed = true)
        every { appLockManager.isDeviceSecure() } returns deviceSecure
        val databaseKeyCipher = mockk<DatabaseKeyCipher>(relaxed = true)
        every { databaseKeyCipher.isInvalidated() } returns keyInvalidated
        val processRestarter = mockk<ProcessRestarter>(relaxed = true)
        val vm = AppLockViewModel(
            context = mockk<Context>(relaxed = true),
            settingsRepository = settingsRepository,
            appLockManager = appLockManager,
            databaseKeyStore = databaseKeyStore,
            databaseKeyCipher = databaseKeyCipher,
            session = mockk<PassphraseSession>(relaxed = true),
            syncScheduler = enqueueingScheduler(),
            processRestarter = processRestarter,
            gate = gate,
        ).also { it.defaultDispatcher = dispatcher }
        return Fixture(vm, settingsRepository, databaseKeyStore, processRestarter)
    }

    /** A [SyncScheduler] whose `syncNow()` returns an [Operation] whose result future resolves. */
    private fun enqueueingScheduler(): SyncScheduler {
        val future = mockk<ListenableFuture<Operation.State.SUCCESS>>()
        every { future.get(any(), any()) } returns Operation.SUCCESS
        val operation = mockk<Operation> { every { result } returns future }
        return mockk<SyncScheduler> { every { syncNow() } returns operation }
    }

    private companion object {
        const val FOREGROUND_AT = 1_000L
    }
}
