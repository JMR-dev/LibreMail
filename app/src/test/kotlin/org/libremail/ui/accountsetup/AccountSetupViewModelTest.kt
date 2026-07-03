// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.accountsetup

import android.content.ActivityNotFoundException
import android.content.Intent
import android.util.Log
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.auth.OAuthResult
import org.libremail.auth.OutlookAuthManager
import org.libremail.domain.repository.AccountRepository
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AccountSetupViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun viewModel(
        outlookAuthManager: OutlookAuthManager = mockk(relaxed = true),
        accountRepository: AccountRepository = mockk(relaxed = true),
    ) = AccountSetupViewModel(outlookAuthManager, accountRepository)

    @Test
    fun `isOutlookConfigured reflects the auth manager`() {
        val manager = mockk<OutlookAuthManager>(relaxed = true)
        every { manager.isConfigured } returns true

        assertTrue(viewModel(outlookAuthManager = manager).isOutlookConfigured)
    }

    @Test
    fun `outlookAuthIntent wraps a successfully built intent`() {
        val manager = mockk<OutlookAuthManager>(relaxed = true)
        every { manager.createAuthIntent() } returns mockk()

        assertTrue(viewModel(outlookAuthManager = manager).outlookAuthIntent().isSuccess)
    }

    @Test
    fun `outlookAuthIntent captures a thrown build failure instead of crashing`() {
        val manager = mockk<OutlookAuthManager>(relaxed = true)
        every { manager.createAuthIntent() } throws ActivityNotFoundException("no browser")

        assertTrue(viewModel(outlookAuthManager = manager).outlookAuthIntent().isFailure)
    }

    @Test
    fun `a missing browser is reported with a browser-specific message`() {
        val vm = viewModel()

        vm.onOutlookLaunchFailed(mockk<ActivityNotFoundException>(relaxed = true))

        assertEquals("No web browser is available for Microsoft sign-in", vm.state.value.error)
        assertEquals(SetupStatus.IDLE, vm.state.value.status)
    }

    @Test
    fun `a generic launch failure surfaces its own message`() {
        val vm = viewModel()

        vm.onOutlookLaunchFailed(IllegalStateException("appauth exploded"))

        assertEquals("appauth exploded", vm.state.value.error)
    }

    @Test
    fun `a launch failure with no message uses a generic fallback`() {
        val vm = viewModel()

        vm.onOutlookLaunchFailed(RuntimeException())

        assertEquals("Couldn't start Microsoft sign-in", vm.state.value.error)
    }

    @Test
    fun `a cancelled sign-in (null result) surfaces a cancellation message`() {
        val vm = viewModel()

        vm.onOutlookResult(null)

        assertEquals("Microsoft sign-in was cancelled", vm.state.value.error)
        assertEquals(SetupStatus.IDLE, vm.state.value.status)
    }

    @Test
    fun `a successful redirect exchanges the token and records the added account`() = runTest(dispatcher) {
        val manager = mockk<OutlookAuthManager>(relaxed = true)
        coEvery { manager.exchangeToken(any()) } returns
            OAuthResult(email = "me@outlook.com", accessToken = "tok", authStateJson = "{}")
        val accounts = mockk<AccountRepository>(relaxed = true)
        coEvery { accounts.addOutlookAccount("me@outlook.com", "tok", "{}") } returns Result.success(listOf("INBOX"))
        val vm = viewModel(outlookAuthManager = manager, accountRepository = accounts)

        vm.onOutlookResult(mockk<Intent>(relaxed = true))
        advanceUntilIdle()

        assertEquals(SetupStatus.DONE, vm.state.value.status)
        assertEquals("outlook:me@outlook.com", vm.state.value.addedAccountId)
        assertNull(vm.state.value.error)
        coVerify { accounts.addOutlookAccount("me@outlook.com", "tok", "{}") }
    }

    @Test
    fun `a failed token exchange returns to idle with the error surfaced`() = runTest(dispatcher) {
        mockkStatic(Log::class)
        every { Log.d(any(), any(), any()) } returns 0
        val manager = mockk<OutlookAuthManager>(relaxed = true)
        coEvery { manager.exchangeToken(any()) } throws IllegalStateException("Token exchange failed")
        val vm = viewModel(outlookAuthManager = manager)

        vm.onOutlookResult(mockk<Intent>(relaxed = true))
        advanceUntilIdle()

        assertEquals(SetupStatus.IDLE, vm.state.value.status)
        assertEquals("Token exchange failed", vm.state.value.error)
        assertNull(vm.state.value.addedAccountId)
    }

    @Test
    fun `a persistence failure after exchange returns to idle with an error`() = runTest(dispatcher) {
        mockkStatic(Log::class)
        every { Log.d(any(), any(), any()) } returns 0
        val manager = mockk<OutlookAuthManager>(relaxed = true)
        coEvery { manager.exchangeToken(any()) } returns
            OAuthResult(email = "me@outlook.com", accessToken = "tok", authStateJson = "{}")
        val accounts = mockk<AccountRepository>(relaxed = true)
        coEvery { accounts.addOutlookAccount(any(), any(), any()) } returns
            Result.failure(RuntimeException("IMAP verification failed"))
        val vm = viewModel(outlookAuthManager = manager, accountRepository = accounts)

        vm.onOutlookResult(mockk<Intent>(relaxed = true))
        advanceUntilIdle()

        assertEquals(SetupStatus.IDLE, vm.state.value.status)
        assertEquals("IMAP verification failed", vm.state.value.error)
    }

    @Test
    fun `consumeError clears a surfaced error`() {
        val vm = viewModel()
        vm.onOutlookResult(null)

        vm.consumeError()

        assertNull(vm.state.value.error)
    }

    @Test
    fun `AccountSetupUiState value semantics`() {
        val state = AccountSetupUiState(status = SetupStatus.DONE, error = "e", addedAccountId = "outlook:me")

        assertEquals(state, state.copy())
        assertEquals(state.hashCode(), state.copy().hashCode())
        assertTrue(state.toString().contains("outlook:me"))
        assertNotEquals(state, state.copy(status = SetupStatus.IDLE))
        assertNotEquals(state, state.copy(error = null))
        assertNotEquals(state, state.copy(addedAccountId = null))
    }
}
