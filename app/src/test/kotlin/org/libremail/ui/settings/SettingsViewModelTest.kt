// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.settings

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
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
import org.libremail.R
import org.libremail.data.security.AppLockManager
import org.libremail.data.security.DatabaseKeyStore
import org.libremail.data.settings.AppSettings
import org.libremail.data.settings.SettingsRepository
import org.libremail.domain.repository.AccountRepository
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers [SettingsViewModel.setAppLock]'s security-critical branches (issue #100): enabling is rejected
 * without a secure device lock; disabling reseals the cache passphrase under the non-auth master key
 * BEFORE dropping the gate, and keeps app-lock on if that reseal fails (so the passphrase is never
 * stranded). JVM-testable with the repo's existing MockK pattern — no device needed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `enabling app-lock without a secure device is rejected and does not persist`() = runTest(dispatcher) {
        val appLockManager = mockk<AppLockManager>()
        every { appLockManager.isDeviceSecure() } returns false
        val settingsRepository = mockk<SettingsRepository>(relaxed = true)
        val vm = viewModel(appLockManager = appLockManager, settingsRepository = settingsRepository)

        vm.setAppLock(true)
        advanceUntilIdle()

        // No secure lock means nothing to authenticate against: reject with a message, persist nothing.
        assertEquals(R.string.app_lock_needs_device_lock, vm.appLockMessage.value)
        coVerify(exactly = 0) { settingsRepository.setAppLock(any()) }
    }

    @Test
    fun `enabling app-lock on a secure device persists the setting`() = runTest(dispatcher) {
        val appLockManager = mockk<AppLockManager>()
        every { appLockManager.isDeviceSecure() } returns true
        val settingsRepository = mockk<SettingsRepository>(relaxed = true)
        val vm = viewModel(appLockManager = appLockManager, settingsRepository = settingsRepository)

        vm.setAppLock(true)
        advanceUntilIdle()

        coVerify { settingsRepository.setAppLock(true) }
        assertNull(vm.appLockMessage.value)
    }

    @Test
    fun `disabling app-lock reseals under the master key before dropping the gate`() = runTest(dispatcher) {
        val databaseKeyStore = mockk<DatabaseKeyStore>(relaxed = true)
        coEvery { databaseKeyStore.hasAuthSealedPassphrase() } returns true
        val settingsRepository = mockk<SettingsRepository>(relaxed = true)
        val vm = viewModel(databaseKeyStore = databaseKeyStore, settingsRepository = settingsRepository)

        vm.setAppLock(false)
        advanceUntilIdle()

        // Reseal so the cache opens without auth again, THEN drop the gate — reversing the order would
        // leave the next launch unable to open a still-auth-sealed cache.
        coVerifyOrder {
            databaseKeyStore.sealWithMaster()
            settingsRepository.setAppLock(false)
        }
    }

    @Test
    fun `disabling app-lock keeps the lock on when resealing fails`() = runTest(dispatcher) {
        val databaseKeyStore = mockk<DatabaseKeyStore>(relaxed = true)
        coEvery { databaseKeyStore.hasAuthSealedPassphrase() } returns true
        coEvery { databaseKeyStore.sealWithMaster() } throws IllegalStateException("keystore busy")
        val settingsRepository = mockk<SettingsRepository>(relaxed = true)
        val vm = viewModel(databaseKeyStore = databaseKeyStore, settingsRepository = settingsRepository)

        vm.setAppLock(false)
        advanceUntilIdle()

        // Resealing failed: surface a message and keep app-lock ON rather than strand the passphrase
        // under a gate we just dropped.
        assertEquals(R.string.app_lock_disable_failed, vm.appLockMessage.value)
        coVerify(exactly = 0) { settingsRepository.setAppLock(false) }
    }

    @Test
    fun `disabling app-lock with no auth seal just drops the gate`() = runTest(dispatcher) {
        val databaseKeyStore = mockk<DatabaseKeyStore>(relaxed = true)
        coEvery { databaseKeyStore.hasAuthSealedPassphrase() } returns false
        val settingsRepository = mockk<SettingsRepository>(relaxed = true)
        val vm = viewModel(databaseKeyStore = databaseKeyStore, settingsRepository = settingsRepository)

        vm.setAppLock(false)
        advanceUntilIdle()

        // Nothing auth-sealed to reseal: skip the master reseal and simply drop the gate.
        coVerify(exactly = 0) { databaseKeyStore.sealWithMaster() }
        coVerify { settingsRepository.setAppLock(false) }
    }

    private fun viewModel(
        appLockManager: AppLockManager = mockk(relaxed = true),
        databaseKeyStore: DatabaseKeyStore = mockk(relaxed = true),
        settingsRepository: SettingsRepository = mockk(relaxed = true),
    ): SettingsViewModel {
        every { settingsRepository.settings } returns flowOf(AppSettings())
        every { settingsRepository.contactsPermissionRequested } returns flowOf(false)
        val accountRepository = mockk<AccountRepository>(relaxed = true)
        every { accountRepository.observeAccounts() } returns flowOf(emptyList())
        return SettingsViewModel(
            accountRepository = accountRepository,
            settingsRepository = settingsRepository,
            appLockManager = appLockManager,
            databaseKeyStore = databaseKeyStore,
            batteryOptimizationManager = mockk(relaxed = true),
            contactsPermissionManager = mockk(relaxed = true),
            syncScheduler = mockk(relaxed = true),
        )
    }
}
