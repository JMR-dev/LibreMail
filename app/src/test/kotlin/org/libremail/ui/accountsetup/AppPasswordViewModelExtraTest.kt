// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.accountsetup

import androidx.lifecycle.SavedStateHandle
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.domain.model.MailProvider
import org.libremail.domain.repository.AccountRepository
import org.libremail.ui.navigation.Routes
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fills the gaps [AppPasswordViewModelTest] leaves: the disclosure/error helpers and the no-message failure. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppPasswordViewModelExtraTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(repo: AccountRepository) = AppPasswordViewModel(
        SavedStateHandle(mapOf(Routes.APP_PASSWORD_ARG_PROVIDER to MailProvider.GMAIL.key)),
        repo,
    )

    @Test
    fun `toggleAdvanced flips the disclosure and consumeError clears the message`() {
        val vm = viewModel(mockk(relaxed = true))

        vm.toggleAdvanced()
        assertTrue(vm.form.value.advancedExpanded)
        vm.toggleAdvanced()
        assertFalse(vm.form.value.advancedExpanded)

        vm.testAndSave() // blank fields -> sets an error
        assertNotEquals(null, vm.form.value.error)
        vm.consumeError()
        assertNull(vm.form.value.error)
    }

    @Test
    fun `a connection failure with no message falls back to a generic one`() = runTest(dispatcher) {
        val repo = mockk<AccountRepository>()
        coEvery { repo.addImapAccount(any(), any()) } returns Result.failure(RuntimeException())
        val vm = viewModel(repo)

        vm.onEmail("user@gmail.com")
        vm.onAppPassword("app-pass")
        vm.testAndSave()

        assertEquals("Could not connect to the server", vm.form.value.error)
        assertEquals(SetupStatus.IDLE, vm.form.value.status)
    }

    @Test
    fun `AppPasswordForm carries value semantics`() {
        val form = AppPasswordForm(
            email = "user@gmail.com",
            appPassword = "app-pass",
            advancedExpanded = true,
            status = SetupStatus.DONE,
            error = "boom",
            addedAccountId = "imap:user@gmail.com",
        )
        assertEquals(form, form.copy())
        assertEquals(form.hashCode(), form.copy().hashCode())
        assertTrue(form.toString().contains("user@gmail.com"))
        assertNotEquals(form, form.copy(status = SetupStatus.IDLE))
        assertNotEquals(form, form.copy(error = null))
    }
}
