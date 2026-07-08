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
import jakarta.mail.AuthenticationFailedException
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
import org.libremail.reporting.AppLog
import org.libremail.reporting.RingLogBuffer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #326 migrated this ViewModel's logging to [AppLog]: a [RingLogBuffer] is installed here and
 * assertions about what got logged are against [logBuffer] — including that a failed sign-in whose
 * throwable carries the account email never lets that email reach the buffer (the seam's
 * `StackTraceScrubber` must redact it).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountSetupViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val logBuffer = RingLogBuffer()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        // android.util.Log is a no-op stub that throws "not mocked" in JVM tests, and AppLog always
        // forwards to it; stub every level up front, then install a real buffer so breadcrumbs can be
        // asserted for real instead of via `verify { Log... }`.
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.d(any(), any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        AppLog.install(logBuffer)
    }

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
    fun `a cancelled sign-in (null result) is a no-op, not an error`() {
        val vm = viewModel()

        vm.onOutlookResult(null)

        // #308: a normal cancel (backing out of the sign-in tab → RESULT_CANCELED, null data) must not
        // raise an error snackbar; it leaves the screen untouched (idle, no error) and only breadcrumbs.
        assertNull(vm.state.value.error)
        assertEquals(SetupStatus.IDLE, vm.state.value.status)
        val entry = logBuffer.snapshot().single()
        assertEquals('D', entry.level)
        assertTrue(entry.message.contains("cancelled"), entry.message)
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
        // Issue #326: the success breadcrumb never carries the account email.
        val entry = logBuffer.snapshot().single()
        assertEquals('I', entry.level)
        assertEquals("Outlook account added", entry.message)
        assertFalse(entry.message.contains("@"))
    }

    @Test
    fun `a failed token exchange returns to idle with the error surfaced`() = runTest(dispatcher) {
        val manager = mockk<OutlookAuthManager>(relaxed = true)
        coEvery { manager.exchangeToken(any()) } throws IllegalStateException("Token exchange failed")
        val vm = viewModel(outlookAuthManager = manager)

        vm.onOutlookResult(mockk<Intent>(relaxed = true))
        advanceUntilIdle()

        assertEquals(SetupStatus.IDLE, vm.state.value.status)
        assertEquals("Token exchange failed", vm.state.value.error)
        assertNull(vm.state.value.addedAccountId)
        // Issue #326: the migrated Log.d -> AppLog.d call records a debug breadcrumb in the buffer
        // (unlike raw Log.d, this survives release builds — only the Logcat mirror is stripped there).
        val entry = logBuffer.snapshot().single()
        assertEquals('D', entry.level)
        assertTrue(entry.message.startsWith("Outlook sign-in failed after redirect"), entry.message)
    }

    @Test
    fun `a persistence failure after exchange returns to idle with an error`() = runTest(dispatcher) {
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
    fun `a token-OK but IMAP-disabled AUTHENTICATE failure surfaces the enable-IMAP prompt`() = runTest(dispatcher) {
        // OAuth succeeds, then the IMAP AUTHENTICATE step is rejected (IMAP off for the mailbox):
        // the actionable prompt replaces the generic error, and no account is marked added (#390).
        val manager = mockk<OutlookAuthManager>(relaxed = true)
        coEvery { manager.exchangeToken(any()) } returns
            OAuthResult(email = "me@outlook.com", accessToken = "tok", authStateJson = "{}")
        val accounts = mockk<AccountRepository>(relaxed = true)
        coEvery { accounts.addOutlookAccount(any(), any(), any()) } returns
            Result.failure(AuthenticationFailedException("AUTHENTICATE failed"))
        val vm = viewModel(outlookAuthManager = manager, accountRepository = accounts)

        vm.onOutlookResult(mockk<Intent>(relaxed = true))
        advanceUntilIdle()

        val prompt = vm.state.value.imapDisabledPrompt
        assertEquals("Outlook", prompt?.brand)
        assertTrue(prompt?.helpUrl?.startsWith("https://support.microsoft.com/") == true)
        assertEquals(SetupStatus.IDLE, vm.state.value.status)
        assertNull(vm.state.value.error)
        assertNull(vm.state.value.addedAccountId)
        // PII-free breadcrumb: the account is referenced by its hashed log ref, never the email.
        val disabledLine = logBuffer.snapshot().single { it.message.contains("IMAP disabled on Outlook sign-in") }
        assertEquals('I', disabledLine.level)
        assertFalse(disabledLine.message.contains("@"), disabledLine.message)

        vm.dismissImapDisabledPrompt()
        assertNull(vm.state.value.imapDisabledPrompt)
    }

    @Test
    fun `a wrong-password style AUTHENTICATE failure keeps the generic error, not the prompt`() = runTest(dispatcher) {
        // A plain OAuth-path failure with no IMAP-disabled signal stays a generic error.
        val manager = mockk<OutlookAuthManager>(relaxed = true)
        coEvery { manager.exchangeToken(any()) } returns
            OAuthResult(email = "me@outlook.com", accessToken = "tok", authStateJson = "{}")
        val accounts = mockk<AccountRepository>(relaxed = true)
        coEvery { accounts.addOutlookAccount(any(), any(), any()) } returns
            Result.failure(RuntimeException("Could not reach the server"))
        val vm = viewModel(outlookAuthManager = manager, accountRepository = accounts)

        vm.onOutlookResult(mockk<Intent>(relaxed = true))
        advanceUntilIdle()

        assertNull(vm.state.value.imapDisabledPrompt)
        assertEquals("Could not reach the server", vm.state.value.error)
    }

    @Test
    fun `a failure whose message carries the account email is scrubbed before it reaches the buffer`() =
        runTest(dispatcher) {
            // Drives a failing Outlook sign-in with a known test email embedded in the thrown
            // exception's message, and asserts no buffer line contains that address: AppLog.d's
            // StackTraceScrubber must redact it from the throwable's recorded stack trace text.
            val knownTestEmail = "me@outlook.com"
            val manager = mockk<OutlookAuthManager>(relaxed = true)
            coEvery { manager.exchangeToken(any()) } throws
                IllegalStateException("Token exchange failed for $knownTestEmail")
            val vm = viewModel(outlookAuthManager = manager)

            vm.onOutlookResult(mockk<Intent>(relaxed = true))
            advanceUntilIdle()

            val snapshot = logBuffer.snapshot()
            assertTrue(snapshot.isNotEmpty())
            snapshot.forEach { entry -> assertFalse(entry.message.contains(knownTestEmail), entry.message) }
        }

    @Test
    fun `consumeError clears a surfaced error`() {
        val vm = viewModel()
        // Surface a real error first (a cancel is now a no-op), then confirm consumeError clears it.
        vm.onOutlookLaunchFailed(IllegalStateException("appauth exploded"))
        assertEquals("appauth exploded", vm.state.value.error)

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
