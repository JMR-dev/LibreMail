// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.settings

import androidx.lifecycle.SavedStateHandle
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.libremail.data.settings.AccountSettingsRepository
import org.libremail.data.settings.AppSettings
import org.libremail.data.settings.SettingsRepository
import org.libremail.data.settings.SignatureRepository
import org.libremail.data.sync.SyncScheduler
import org.libremail.domain.model.Account
import org.libremail.domain.model.AccountSettings
import org.libremail.domain.model.AuthType
import org.libremail.domain.model.MailSecurity
import org.libremail.domain.model.ServerConfig
import org.libremail.domain.model.Signature
import org.libremail.domain.repository.AccountRepository
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class AccountSettingsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val account = Account(
        id = ACCOUNT,
        email = "me@example.org",
        displayName = "Me",
        authType = AuthType.PASSWORD_IMAP,
        imap = ServerConfig("imap.example.org", 993, MailSecurity.SSL_TLS),
        smtp = ServerConfig("smtp.example.org", 465, MailSecurity.SSL_TLS),
    )

    private fun signature(id: String, name: String, isDefault: Boolean) =
        Signature(id = id, accountId = ACCOUNT, name = name, html = "<p>$name</p>", isDefault = isDefault)

    private fun viewModel(
        accountRepository: AccountRepository = mockk(relaxed = true),
        accountSettingsRepository: AccountSettingsRepository = mockk(relaxed = true),
        signatureRepository: SignatureRepository = mockk(relaxed = true),
        syncScheduler: SyncScheduler = mockk(relaxed = true),
        settingsRepository: SettingsRepository = mockk(relaxed = true),
        accounts: List<Account> = listOf(account),
        settings: AccountSettings = AccountSettings(ACCOUNT),
        appSettings: AppSettings = AppSettings(),
        signatures: List<Signature> = emptyList(),
    ): AccountSettingsViewModel {
        every { accountRepository.observeAccounts() } returns MutableStateFlow(accounts)
        every { accountSettingsRepository.observe(ACCOUNT) } returns MutableStateFlow(settings)
        every { settingsRepository.settings } returns MutableStateFlow(appSettings)
        every { signatureRepository.observeForAccount(ACCOUNT) } returns MutableStateFlow(signatures)
        return AccountSettingsViewModel(
            savedStateHandle = SavedStateHandle(mapOf("accountId" to ACCOUNT)),
            accountRepository = accountRepository,
            accountSettingsRepository = accountSettingsRepository,
            signatureRepository = signatureRepository,
            syncScheduler = syncScheduler,
            settingsRepository = settingsRepository,
        )
    }

    @Test
    fun `account resolves the matching account from the list`() = runTest(dispatcher) {
        val other = account.copy(id = "imap:other", email = "other@example.org")
        val vm = viewModel(accounts = listOf(other, account))

        backgroundScope.launch { vm.account.collect {} }
        runCurrent()

        assertEquals(ACCOUNT, vm.account.value?.id)
    }

    @Test
    fun `settings and derived signature summary reflect the repositories`() = runTest(dispatcher) {
        val vm = viewModel(
            settings = AccountSettings(ACCOUNT, notificationsEnabled = false),
            signatures = listOf(
                signature("a", "First", isDefault = false),
                signature("b", "Default", isDefault = true),
            ),
        )

        backgroundScope.launch { vm.settings.collect {} }
        backgroundScope.launch { vm.signatureCount.collect {} }
        backgroundScope.launch { vm.defaultSignatureName.collect {} }
        runCurrent()

        assertEquals(false, vm.settings.value.notificationsEnabled)
        assertEquals(2, vm.signatureCount.value)
        // The default signature wins the summary line.
        assertEquals("Default", vm.defaultSignatureName.value)
    }

    @Test
    fun `defaultSignatureName falls back to the first signature when none is marked default`() = runTest(dispatcher) {
        val vm = viewModel(signatures = listOf(signature("a", "OnlyOne", isDefault = false)))

        backgroundScope.launch { vm.defaultSignatureName.collect {} }
        runCurrent()

        assertEquals("OnlyOne", vm.defaultSignatureName.value)
    }

    @Test
    fun `defaultSignatureName is blank when there are no signatures`() = runTest(dispatcher) {
        val vm = viewModel(signatures = emptyList())

        backgroundScope.launch { vm.defaultSignatureName.collect {} }
        runCurrent()

        assertEquals("", vm.defaultSignatureName.value)
    }

    @Test
    fun `isDefaultAccount tracks whether this account holds the default preference`() = runTest(dispatcher) {
        val vm = viewModel(appSettings = AppSettings(defaultAccountId = ACCOUNT))

        backgroundScope.launch { vm.isDefaultAccount.collect {} }
        runCurrent()

        assertEquals(true, vm.isDefaultAccount.value)
    }

    @Test
    fun `notificationChannelId is derived from the account id`() {
        assertEquals("new_mail:$ACCOUNT", viewModel().notificationChannelId)
    }

    @Test
    fun `setSignatureEnabled and setNotificationsEnabled delegate to the settings repository`() = runTest(dispatcher) {
        val settings = mockk<AccountSettingsRepository>(relaxed = true)
        every { settings.observe(ACCOUNT) } returns MutableStateFlow(AccountSettings(ACCOUNT))
        val vm = viewModel(accountSettingsRepository = settings)

        vm.setSignatureEnabled(false)
        vm.setNotificationsEnabled(false)
        advanceUntilIdle()

        coVerify { settings.setSignatureEnabled(ACCOUNT, false) }
        coVerify { settings.setNotificationsEnabled(ACCOUNT, false) }
    }

    @Test
    fun `setRetentionCount persists then prunes and resets backfill so the new cap takes effect`() =
        runTest(dispatcher) {
            val settings = mockk<AccountSettingsRepository>(relaxed = true)
            every { settings.observe(ACCOUNT) } returns MutableStateFlow(AccountSettings(ACCOUNT))
            val accounts = mockk<AccountRepository>(relaxed = true)
            every { accounts.observeAccounts() } returns MutableStateFlow(listOf(account))
            val syncScheduler = mockk<SyncScheduler>(relaxed = true)
            val vm = viewModel(
                accountRepository = accounts,
                accountSettingsRepository = settings,
                syncScheduler = syncScheduler,
            )

            vm.setRetentionCount(500)
            advanceUntilIdle()

            coVerifyOrder {
                settings.setRetentionCount(ACCOUNT, 500)
                syncScheduler.pruneNow()
                accounts.resetBackfillProgress(ACCOUNT)
            }
        }

    @Test
    fun `setRetentionMonths persists then prunes and resets backfill`() = runTest(dispatcher) {
        val settings = mockk<AccountSettingsRepository>(relaxed = true)
        every { settings.observe(ACCOUNT) } returns MutableStateFlow(AccountSettings(ACCOUNT))
        val accounts = mockk<AccountRepository>(relaxed = true)
        every { accounts.observeAccounts() } returns MutableStateFlow(listOf(account))
        val syncScheduler = mockk<SyncScheduler>(relaxed = true)
        val vm = viewModel(
            accountRepository = accounts,
            accountSettingsRepository = settings,
            syncScheduler = syncScheduler,
        )

        vm.setRetentionMonths(null)
        advanceUntilIdle()

        coVerifyOrder {
            settings.setRetentionMonths(ACCOUNT, null)
            syncScheduler.pruneNow()
            accounts.resetBackfillProgress(ACCOUNT)
        }
    }

    @Test
    fun `setDefaultAccount persists this account as the default when turned on`() = runTest(dispatcher) {
        val settingsRepository = mockk<SettingsRepository>(relaxed = true)
        every { settingsRepository.settings } returns MutableStateFlow(AppSettings())
        val vm = viewModel(settingsRepository = settingsRepository)

        vm.setDefaultAccount(true)
        advanceUntilIdle()

        coVerify { settingsRepository.setDefaultAccountId(ACCOUNT) }
    }

    @Test
    fun `setDefaultAccount clears the default when turned off`() = runTest(dispatcher) {
        val settingsRepository = mockk<SettingsRepository>(relaxed = true)
        every { settingsRepository.settings } returns MutableStateFlow(AppSettings())
        val vm = viewModel(settingsRepository = settingsRepository)

        vm.setDefaultAccount(false)
        advanceUntilIdle()

        coVerify { settingsRepository.clearDefaultAccountId(ACCOUNT) }
    }

    @Test
    fun `removeAccount deletes the account, clears the stranded default, then notifies the caller`() =
        runTest(dispatcher) {
            val accounts = mockk<AccountRepository>(relaxed = true)
            every { accounts.observeAccounts() } returns MutableStateFlow(listOf(account))
            val settingsRepository = mockk<SettingsRepository>(relaxed = true)
            every { settingsRepository.settings } returns MutableStateFlow(AppSettings())
            val vm = viewModel(accountRepository = accounts, settingsRepository = settingsRepository)
            var removed = false

            vm.removeAccount { removed = true }
            advanceUntilIdle()

            coVerifyOrder {
                accounts.deleteAccount(ACCOUNT)
                settingsRepository.clearDefaultAccountId(ACCOUNT)
            }
            assertEquals(true, removed)
        }

    private companion object {
        const val ACCOUNT = "imap:me@example.org"
    }
}
