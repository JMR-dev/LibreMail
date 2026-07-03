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
 * [AppViewModel] decides two independent things, each resolved once at launch: the top-level start
 * destination (onboarding vs. mailbox, from the stored account count) and, since #172, whether the
 * user has already agreed to the license (consulted by `onboardingGraph()` in `LibreMailApp.kt` to
 * pick between `Routes.ONBOARDING_LICENSE` and `Routes.ONBOARDING_WELCOME`). Both repositories are
 * mocked: [AccountRepository] and [SettingsRepository] are interfaces/classes this ViewModel only
 * reads from, and `SettingsRepository` itself needs a real `Context` it doesn't get in a JVM test.
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
    fun `no accounts resolves onboarding as the start destination`() = runTest(testDispatcher) {
        val vm = AppViewModel(accountRepository(emptyList()), settingsRepository())

        assertEquals(Routes.ONBOARDING, vm.startDestination.value)
    }

    @Test
    fun `an existing account resolves the mailbox as the start destination`() = runTest(testDispatcher) {
        val vm = AppViewModel(accountRepository(listOf(account())), settingsRepository())

        assertEquals(Routes.MAILBOX, vm.startDestination.value)
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
}
