// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.accountsetup

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
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
import org.libremail.domain.model.MailSecurity
import org.libremail.domain.repository.AccountRepository
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ManualSetupViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun filled(vm: ManualSetupViewModel) {
        vm.onEmail("  user@example.org  ")
        vm.onPassword("secret")
        vm.onImapHost("  imap.example.org ")
        vm.onSmtpHost(" smtp.example.org ")
    }

    @Test
    fun `starts with the standard secure-port defaults`() {
        val form = ManualSetupViewModel(mockk(relaxed = true)).form.value

        assertEquals("993", form.imapPort)
        assertEquals("465", form.smtpPort)
        assertEquals(MailSecurity.SSL_TLS, form.imapSecurity)
        assertEquals(MailSecurity.SSL_TLS, form.smtpSecurity)
        assertEquals(SetupStatus.IDLE, form.status)
        assertFalse(form.advancedExpanded)
    }

    @Test
    fun `field setters update the form`() {
        val vm = ManualSetupViewModel(mockk(relaxed = true))

        vm.onEmail("a@b.org")
        vm.onPassword("pw")
        vm.onImapHost("imap.host")
        vm.onSmtpHost("smtp.host")
        vm.onImapSecurity(MailSecurity.STARTTLS)
        vm.onSmtpSecurity(MailSecurity.NONE)

        val f = vm.form.value
        assertEquals("a@b.org", f.email)
        assertEquals("pw", f.password)
        assertEquals("imap.host", f.imapHost)
        assertEquals("smtp.host", f.smtpHost)
        assertEquals(MailSecurity.STARTTLS, f.imapSecurity)
        assertEquals(MailSecurity.NONE, f.smtpSecurity)
    }

    @Test
    fun `port inputs keep only digits and cap at five characters`() {
        val vm = ManualSetupViewModel(mockk(relaxed = true))

        vm.onImapPort("9a9b3xyz")
        vm.onSmtpPort("1234567")

        assertEquals("993", vm.form.value.imapPort)
        assertEquals("12345", vm.form.value.smtpPort)
    }

    @Test
    fun `toggleAdvanced flips the disclosure and consumeError clears the message`() {
        val vm = ManualSetupViewModel(mockk(relaxed = true))

        vm.toggleAdvanced()
        assertTrue(vm.form.value.advancedExpanded)
        vm.toggleAdvanced()
        assertFalse(vm.form.value.advancedExpanded)

        vm.testAndSave() // invalid -> sets an error
        assertNotEquals(null, vm.form.value.error)
        vm.consumeError()
        assertNull(vm.form.value.error)
    }

    @Test
    fun `testAndSave rejects an incomplete form without contacting the server`() = runTest(dispatcher) {
        val repo = mockk<AccountRepository>(relaxed = true)
        val vm = ManualSetupViewModel(repo)

        vm.onEmail("user@example.org") // missing password + hosts
        vm.testAndSave()

        assertEquals("Enter email, password, and both servers", vm.form.value.error)
        assertEquals(SetupStatus.IDLE, vm.form.value.status)
        coVerify(exactly = 0) { repo.addImapAccount(any(), any()) }
    }

    @Test
    fun `testAndSave builds a trimmed account and marks it added on success`() = runTest(dispatcher) {
        val repo = mockk<AccountRepository>()
        val account = slot<Account>()
        coEvery { repo.addImapAccount(capture(account), "secret") } returns Result.success(listOf("INBOX"))
        val vm = ManualSetupViewModel(repo)
        filled(vm)
        vm.onImapPort("143")
        vm.onImapSecurity(MailSecurity.STARTTLS)

        vm.testAndSave()

        assertEquals("imap:user@example.org", account.captured.id)
        assertEquals("user@example.org", account.captured.email)
        assertEquals("imap.example.org", account.captured.imap.host)
        assertEquals(143, account.captured.imap.port)
        assertEquals(MailSecurity.STARTTLS, account.captured.imap.security)
        assertEquals("smtp.example.org", account.captured.smtp.host)
        assertEquals(465, account.captured.smtp.port)
        assertEquals(SetupStatus.DONE, vm.form.value.status)
        assertEquals("imap:user@example.org", vm.form.value.addedAccountId)
    }

    @Test
    fun `testAndSave lowercases the domain in the id but keeps the local-part casing`() = runTest(dispatcher) {
        // Generic IMAP servers may treat the local part case-sensitively, so only the domain is
        // lowercased for the id (issue #305); the displayed email keeps the typed casing.
        val repo = mockk<AccountRepository>()
        val account = slot<Account>()
        coEvery { repo.addImapAccount(capture(account), any()) } returns Result.success(emptyList())
        val vm = ManualSetupViewModel(repo)
        vm.onEmail("  User@Example.ORG  ")
        vm.onPassword("secret")
        vm.onImapHost("imap.example.org")
        vm.onSmtpHost("smtp.example.org")

        vm.testAndSave()

        assertEquals("imap:User@example.org", account.captured.id)
        assertEquals("User@Example.ORG", account.captured.email)
    }

    @Test
    fun `testAndSave falls back to the default ports when the port fields are blank`() = runTest(dispatcher) {
        val repo = mockk<AccountRepository>()
        val account = slot<Account>()
        coEvery { repo.addImapAccount(capture(account), any()) } returns Result.success(emptyList())
        val vm = ManualSetupViewModel(repo)
        filled(vm)
        vm.onImapPort("")
        vm.onSmtpPort("")

        vm.testAndSave()

        assertEquals(993, account.captured.imap.port)
        assertEquals(465, account.captured.smtp.port)
    }

    @Test
    fun `testAndSave surfaces a connection failure inline and leaves the account unadded`() = runTest(dispatcher) {
        val repo = mockk<AccountRepository>()
        coEvery { repo.addImapAccount(any(), any()) } returns Result.failure(RuntimeException("Login failed"))
        val vm = ManualSetupViewModel(repo)
        filled(vm)

        vm.testAndSave()

        assertEquals("Login failed", vm.form.value.error)
        assertEquals(SetupStatus.IDLE, vm.form.value.status)
        assertNull(vm.form.value.addedAccountId)
    }

    @Test
    fun `testAndSave uses a generic message when the failure has none`() = runTest(dispatcher) {
        val repo = mockk<AccountRepository>()
        coEvery { repo.addImapAccount(any(), any()) } returns Result.failure(RuntimeException())
        val vm = ManualSetupViewModel(repo)
        filled(vm)

        vm.testAndSave()

        assertEquals("Could not connect to the server", vm.form.value.error)
    }

    @Test
    fun `ManualSetupForm isValid requires email, password, and both hosts`() {
        val complete = ManualSetupForm(
            email = "a@b.org",
            password = "pw",
            imapHost = "imap",
            smtpHost = "smtp",
        )
        assertTrue(complete.isValid)
        assertFalse(complete.copy(email = "").isValid)
        assertFalse(complete.copy(password = "").isValid)
        assertFalse(complete.copy(imapHost = "").isValid)
        assertFalse(complete.copy(smtpHost = "").isValid)
    }

    @Test
    fun `ManualSetupForm value semantics`() {
        val form = ManualSetupForm(
            email = "a@b.org",
            password = "pw",
            imapHost = "imap",
            imapPort = "143",
            imapSecurity = MailSecurity.STARTTLS,
            smtpHost = "smtp",
            smtpPort = "587",
            smtpSecurity = MailSecurity.NONE,
            advancedExpanded = true,
            status = SetupStatus.DONE,
            error = "boom",
            addedAccountId = "imap:a@b.org",
        )

        assertEquals(form, form.copy())
        assertEquals(form.hashCode(), form.copy().hashCode())
        assertTrue(form.toString().contains("a@b.org"))
        assertNotEquals(form, form.copy(status = SetupStatus.IDLE))
        assertNotEquals(form, form.copy(advancedExpanded = false))
        assertNotEquals(form, form.copy(addedAccountId = null))
    }
}
