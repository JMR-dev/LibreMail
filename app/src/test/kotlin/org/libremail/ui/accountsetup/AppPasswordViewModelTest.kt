// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.accountsetup

import androidx.lifecycle.SavedStateHandle
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import jakarta.mail.AuthenticationFailedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.domain.model.Account
import org.libremail.domain.model.MailProvider
import org.libremail.domain.model.MailSecurity
import org.libremail.domain.repository.AccountRepository
import org.libremail.ui.navigation.Routes
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppPasswordViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        // The IMAP-disabled branch breadcrumbs via AppLog -> android.util.Log, a throwing stub under
        // plain JVM tests. Mock it fully-qualified so this file never imports android.util.Log (which
        // detekt's ForbiddenImport guard would flag).
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun viewModel(repo: AccountRepository, providerKey: String = MailProvider.GMAIL.key) = AppPasswordViewModel(
        SavedStateHandle(mapOf(Routes.APP_PASSWORD_ARG_PROVIDER to providerKey)),
        repo,
    )

    @Test
    fun `provider is resolved from the nav argument`() {
        val vm = viewModel(mockk(relaxed = true), providerKey = "icloud")
        assertEquals(MailProvider.ICLOUD, vm.provider)
    }

    @Test
    fun `valid input builds the preset account and persists it`() = runTest(testDispatcher) {
        val repo = mockk<AccountRepository>()
        val account = slot<Account>()
        coEvery { repo.addImapAccount(capture(account), "app-pass") } returns Result.success(listOf("INBOX"))
        val vm = viewModel(repo)

        vm.onEmail("  user@gmail.com ")
        vm.onAppPassword("app-pass")
        vm.testAndSave()

        coVerify { repo.addImapAccount(any(), "app-pass") }
        // Servers come from the Gmail preset, not from any user input.
        assertEquals("imap.gmail.com", account.captured.imap.host)
        assertEquals(993, account.captured.imap.port)
        assertEquals(MailSecurity.SSL_TLS, account.captured.imap.security)
        assertEquals("smtp.gmail.com", account.captured.smtp.host)
        assertEquals(587, account.captured.smtp.port)
        assertEquals(MailSecurity.STARTTLS, account.captured.smtp.security)
        assertEquals("user@gmail.com", account.captured.email)

        assertEquals(SetupStatus.DONE, vm.form.value.status)
        assertEquals("imap:user@gmail.com", vm.form.value.addedAccountId)
    }

    @Test
    fun `blank email or app password surfaces an error without contacting the server`() = runTest(testDispatcher) {
        val repo = mockk<AccountRepository>(relaxed = true)
        val vm = viewModel(repo)

        vm.onEmail("")
        vm.onAppPassword("app-pass")
        vm.testAndSave()

        assertTrue(vm.form.value.error != null)
        assertEquals(SetupStatus.IDLE, vm.form.value.status)
        assertNull(vm.form.value.addedAccountId)
        coVerify(exactly = 0) { repo.addImapAccount(any(), any()) }
    }

    @Test
    fun `a connection failure is surfaced inline and the account is not marked added`() = runTest(testDispatcher) {
        val repo = mockk<AccountRepository>()
        coEvery { repo.addImapAccount(any(), any()) } returns Result.failure(RuntimeException("Login failed"))
        val vm = viewModel(repo)

        vm.onEmail("user@gmail.com")
        vm.onAppPassword("wrong")
        vm.testAndSave()

        assertEquals("Login failed", vm.form.value.error)
        assertEquals(SetupStatus.IDLE, vm.form.value.status)
        assertNull(vm.form.value.addedAccountId)
    }

    @Test
    fun `an IMAP-disabled failure surfaces the enable-IMAP prompt instead of a generic error`() =
        runTest(testDispatcher) {
            val repo = mockk<AccountRepository>()
            coEvery { repo.addImapAccount(any(), any()) } returns
                Result.failure(AuthenticationFailedException("Your account is not enabled for IMAP use"))
            val vm = viewModel(repo) // Gmail preset

            vm.onEmail("user@gmail.com")
            vm.onAppPassword("app-pass")
            vm.testAndSave()

            val prompt = vm.form.value.imapDisabledPrompt
            assertEquals(MailProvider.GMAIL.displayName, prompt?.brand)
            assertTrue(prompt?.helpUrl?.startsWith("https://support.google.com/") == true)
            assertNull(vm.form.value.error)
            assertEquals(SetupStatus.IDLE, vm.form.value.status)
            assertNull(vm.form.value.addedAccountId)

            vm.dismissImapDisabledPrompt()
            assertNull(vm.form.value.imapDisabledPrompt)
        }

    @Test
    fun `an unknown provider key surfaces an error and never contacts the server`() = runTest(testDispatcher) {
        val repo = mockk<AccountRepository>(relaxed = true)
        val vm = viewModel(repo, providerKey = "bogus")

        assertNull(vm.provider)
        vm.onEmail("user@example.com")
        vm.onAppPassword("app-pass")
        vm.testAndSave()

        assertTrue(vm.form.value.error != null)
        coVerify(exactly = 0) { repo.addImapAccount(any(), any()) }
    }

    @Test
    fun `isValid requires both an email and an app password`() {
        val vm = viewModel(mockk(relaxed = true))
        assertTrue(!vm.form.value.isValid)
        vm.onEmail("user@gmail.com")
        assertTrue(!vm.form.value.isValid)
        vm.onAppPassword("app-pass")
        assertTrue(vm.form.value.isValid)
    }
}
