// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui

import io.mockk.every
import io.mockk.mockk
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
import org.libremail.data.settings.AppSettings
import org.libremail.data.settings.SettingsRepository
import org.libremail.domain.model.Account
import org.libremail.domain.model.AuthType
import org.libremail.domain.model.MailSecurity
import org.libremail.domain.model.ServerConfig
import org.libremail.domain.repository.AccountRepository
import org.libremail.ui.navigation.Routes
import kotlin.test.assertEquals

/**
 * [AppViewModel] resolves, once at launch, the top-level start destination plus the two facts it
 * derives from: whether any account exists and whether the license has been accepted (#172). The
 * start destination is onboarding unless the license is accepted AND an account exists — so an upgrade
 * user with accounts but no acceptance is still routed through the license gate, not the mailbox.
 * Both repositories are mocked: [AccountRepository] and [SettingsRepository] are types this ViewModel
 * only reads from, and `SettingsRepository` itself needs a real `Context` it doesn't get in a JVM test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(testDispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun account(id: String = "imap:me@example.com") = Account(
        id = id,
        email = "me@example.com",
        displayName = "me@example.com",
        authType = AuthType.PASSWORD_IMAP,
        imap = ServerConfig("imap.example.com", 993, MailSecurity.SSL_TLS),
        smtp = ServerConfig("smtp.example.com", 587, MailSecurity.STARTTLS),
    )

    private fun accountRepository(accounts: List<Account> = emptyList()) = mockk<AccountRepository> {
        every { observeAccounts() } returns flowOf(accounts)
    }

    private fun settingsRepository(licenseAccepted: Boolean = false) = mockk<SettingsRepository> {
        every { settings } returns flowOf(AppSettings(licenseAccepted = licenseAccepted))
    }

    @Test
    fun `no accounts routes to onboarding regardless of license state`() = runTest(testDispatcher) {
        val unaccepted = AppViewModel(accountRepository(emptyList()), settingsRepository(licenseAccepted = false))
        val accepted = AppViewModel(accountRepository(emptyList()), settingsRepository(licenseAccepted = true))

        assertEquals(Routes.ONBOARDING, unaccepted.startDestination.value)
        assertEquals(Routes.ONBOARDING, accepted.startDestination.value)
    }

    @Test
    fun `existing accounts route to the mailbox once the license is accepted`() = runTest(testDispatcher) {
        val vm = AppViewModel(accountRepository(listOf(account())), settingsRepository(licenseAccepted = true))

        assertEquals(Routes.MAILBOX, vm.startDestination.value)
    }

    @Test
    fun `existing accounts with an unaccepted license still route to onboarding (upgrade license gate)`() =
        runTest(testDispatcher) {
            // Regression for the post-batch review finding: a pre-#172 install already has accounts, so
            // the old account-count-only rule sent it straight to the mailbox and skipped the license.
            // The license gate must win, forcing onboarding (which begins at ONBOARDING_LICENSE).
            val vm = AppViewModel(accountRepository(listOf(account())), settingsRepository(licenseAccepted = false))

            assertEquals(Routes.ONBOARDING, vm.startDestination.value)
        }

    @Test
    fun `license acceptance defaults to not accepted`() = runTest(testDispatcher) {
        val vm = AppViewModel(accountRepository(), settingsRepository(licenseAccepted = false))

        assertEquals(false, vm.licenseAccepted.value)
    }

    @Test
    fun `a previously accepted license is reflected so onboarding can skip straight to welcome`() =
        runTest(testDispatcher) {
            val vm = AppViewModel(accountRepository(), settingsRepository(licenseAccepted = true))

            assertEquals(true, vm.licenseAccepted.value)
        }

    @Test
    fun `hasAccounts reflects whether any account is stored`() = runTest(testDispatcher) {
        val empty = AppViewModel(accountRepository(emptyList()), settingsRepository())
        val withAccount = AppViewModel(accountRepository(listOf(account())), settingsRepository())

        assertEquals(false, empty.hasAccounts.value)
        assertEquals(true, withAccount.hasAccounts.value)
    }
}
