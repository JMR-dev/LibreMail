// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.settings

import android.content.Intent
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.contacts.ContactsPermissionManager
import org.libremail.data.settings.AppSettings
import org.libremail.data.settings.FetchPolicy
import org.libremail.data.settings.SettingsRepository
import org.libremail.data.sync.SyncScheduler
import org.libremail.domain.model.Account
import org.libremail.domain.model.AuthType
import org.libremail.domain.model.MailSecurity
import org.libremail.domain.model.ServerConfig
import org.libremail.domain.repository.AccountRepository
import org.libremail.push.BatteryOptimizationManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Covers the read-through state and the thin settings delegators of [SettingsViewModel]. The
 * security-critical `setAppLock` branches have their own dedicated coverage in [SettingsViewModelTest];
 * these lock in everything else so the whole ViewModel clears the coverage bar.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelDelegationTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val account = Account(
        id = "imap:a",
        email = "a@example.org",
        displayName = "A",
        authType = AuthType.PASSWORD_IMAP,
        imap = ServerConfig("imap.example.org", 993, MailSecurity.SSL_TLS),
        smtp = ServerConfig("smtp.example.org", 465, MailSecurity.SSL_TLS),
    )

    private fun viewModel(
        accountRepository: AccountRepository = mockk(relaxed = true),
        settingsRepository: SettingsRepository = mockk(relaxed = true),
        batteryOptimizationManager: BatteryOptimizationManager = mockk(relaxed = true),
        contactsPermissionManager: ContactsPermissionManager = mockk(relaxed = true),
        syncScheduler: SyncScheduler = mockk(relaxed = true),
        accounts: List<Account> = listOf(account),
        settings: AppSettings = AppSettings(),
        contactsRequested: Boolean = false,
    ): SettingsViewModel {
        every { accountRepository.observeAccounts() } returns MutableStateFlow(accounts)
        every { settingsRepository.settings } returns MutableStateFlow(settings)
        every { settingsRepository.contactsPermissionRequested } returns MutableStateFlow(contactsRequested)
        return SettingsViewModel(
            accountRepository = accountRepository,
            settingsRepository = settingsRepository,
            appLockManager = mockk(relaxed = true),
            databaseKeyStore = mockk(relaxed = true),
            batteryOptimizationManager = batteryOptimizationManager,
            contactsPermissionManager = contactsPermissionManager,
            syncScheduler = syncScheduler,
        )
    }

    @Test
    fun `accounts, settings, and contacts-requested expose the repository streams`() = runTest(dispatcher) {
        val vm = viewModel(
            settings = AppSettings(dynamicColor = false, pushIdle = false),
            contactsRequested = true,
        )

        backgroundScope.launch { vm.accounts.collect {} }
        backgroundScope.launch { vm.settings.collect {} }
        backgroundScope.launch { vm.contactsPermissionRequested.collect {} }
        runCurrent()

        assertEquals(listOf("imap:a"), vm.accounts.value.map { it.id })
        assertFalse(vm.settings.value.dynamicColor)
        assertTrue(vm.contactsPermissionRequested.value)
    }

    @Test
    fun `toggleAdvanced flips the advanced disclosure`() {
        val vm = viewModel()

        assertFalse(vm.advancedExpanded.value)
        vm.toggleAdvanced()
        assertTrue(vm.advancedExpanded.value)
        vm.toggleAdvanced()
        assertFalse(vm.advancedExpanded.value)
    }

    @Test
    fun `battery-unrestricted state seeds from and refreshes via the manager`() {
        val battery = mockk<BatteryOptimizationManager>(relaxed = true)
        every { battery.isIgnoringBatteryOptimizations() } returnsMany listOf(false, true)
        val vm = viewModel(batteryOptimizationManager = battery)

        assertFalse(vm.batteryUnrestricted.value)
        vm.refreshBatteryStatus()
        assertTrue(vm.batteryUnrestricted.value)
    }

    @Test
    fun `intent and permission reads delegate straight to their managers`() {
        val batteryIntent = mockk<Intent>()
        val contactsIntent = mockk<Intent>()
        val battery = mockk<BatteryOptimizationManager>(relaxed = true) {
            every { settingsIntent() } returns batteryIntent
        }
        val contacts = mockk<ContactsPermissionManager>(relaxed = true) {
            every { settingsIntent() } returns contactsIntent
            every { hasPermission() } returns true
        }
        val vm = viewModel(batteryOptimizationManager = battery, contactsPermissionManager = contacts)

        assertSame(batteryIntent, vm.batterySettingsIntent())
        assertSame(contactsIntent, vm.contactsSettingsIntent())
        assertTrue(vm.hasContactsPermission())
    }

    @Test
    fun `simple toggles delegate to the settings repository`() = runTest(dispatcher) {
        val repo = mockk<SettingsRepository>(relaxed = true)
        val vm = viewModel(settingsRepository = repo)

        vm.setDynamicColor(true)
        vm.setNewMailNotifications(false)
        vm.setPushIdle(true)
        vm.setAllowStartTls(true)
        vm.setLoadRemoteImages(true)
        vm.setEncryptCache(true)
        vm.setIncludeInBackup(false)
        vm.setFetchPolicy(FetchPolicy.ON_DEMAND)
        vm.markContactsPermissionRequested()
        advanceUntilIdle()

        coVerify { repo.setDynamicColor(true) }
        coVerify { repo.setNewMailNotifications(false) }
        coVerify { repo.setPushIdle(true) }
        coVerify { repo.setAllowStartTls(true) }
        coVerify { repo.setLoadRemoteImages(true) }
        coVerify { repo.setEncryptCache(true) }
        coVerify { repo.setIncludeInBackup(false) }
        coVerify { repo.setFetchPolicy(FetchPolicy.ON_DEMAND) }
        coVerify { repo.setContactsPermissionRequested(true) }
    }

    @Test
    fun `clearAppLockMessage resets the transient message`() {
        val vm = viewModel()

        vm.clearAppLockMessage()

        assertNull(vm.appLockMessage.value)
    }

    @Test
    fun `global retention changes persist then prune and reset backfill for all accounts`() = runTest(dispatcher) {
        val repo = mockk<SettingsRepository>(relaxed = true)
        val accounts = mockk<AccountRepository>(relaxed = true)
        every { accounts.observeAccounts() } returns MutableStateFlow(listOf(account))
        val syncScheduler = mockk<SyncScheduler>(relaxed = true)
        val vm = viewModel(accountRepository = accounts, settingsRepository = repo, syncScheduler = syncScheduler)

        vm.setRetentionCount(1000)
        advanceUntilIdle()
        coVerifyOrder {
            repo.setRetentionCount(1000)
            syncScheduler.pruneNow()
            accounts.resetBackfillProgress(null)
        }

        vm.setRetentionMonths(12)
        advanceUntilIdle()
        coVerifyOrder {
            repo.setRetentionMonths(12)
            syncScheduler.pruneNow()
            accounts.resetBackfillProgress(null)
        }
    }

    @Test
    fun `contactsPermissionRequested defaults to false before the stream emits`() {
        val settingsRepository = mockk<SettingsRepository>(relaxed = true)
        every { settingsRepository.settings } returns flowOf(AppSettings())
        every { settingsRepository.contactsPermissionRequested } returns flowOf(true)
        val vm = viewModel(settingsRepository = settingsRepository)

        // Before anyone collects, the StateFlow shows its seed value.
        assertFalse(vm.contactsPermissionRequested.value)
    }
}
