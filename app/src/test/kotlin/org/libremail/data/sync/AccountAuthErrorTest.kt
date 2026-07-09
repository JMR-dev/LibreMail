// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.R
import org.libremail.data.local.dao.AccountDao
import org.libremail.domain.model.Account
import org.libremail.domain.model.AuthType
import org.libremail.domain.model.ImapConnectionParams
import org.libremail.domain.model.MailSecurity
import org.libremail.domain.model.ServerConfig
import org.libremail.mail.AuthCadencePolicy
import org.libremail.mail.AuthThrottleGate
import org.libremail.mail.ProviderAuthPolicy
import org.libremail.reporting.AppLog
import org.libremail.reporting.RingLogBuffer
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [markAccountErroredIfLatched] (issue #362) must persist the user-facing "remove and re-add" error onto
 * an account whose proactive auth circuit has **latched** — exactly once, PII-free — and report the latch
 * so the caller stops syncing; and it must be a no-op for a still-ramping or healthy account.
 */
class AccountAuthErrorTest {

    private val logBuffer = RingLogBuffer()

    /** A gate that treats the test host as a gated Yahoo/AOL account, latching after the threshold. */
    private fun latchingGate(): AuthThrottleGate = AuthThrottleGate(
        nowMillis = { 0L },
        random = { 0.0 },
        policyForHost = { ProviderAuthPolicy.forHost(YAHOO_HOST) },
    )

    private fun params() = ImapConnectionParams(YAHOO_HOST, PORT, MailSecurity.SSL_TLS, "user@yahoo.com", "s", false)

    private val account = Account(
        id = "acct",
        email = "user@yahoo.com",
        displayName = "User",
        authType = AuthType.PASSWORD_IMAP,
        imap = ServerConfig(YAHOO_HOST, PORT, MailSecurity.SSL_TLS),
        smtp = ServerConfig("smtp.mail.yahoo.com", 465, MailSecurity.SSL_TLS),
    )

    private fun context(): Context = mockk<Context>().apply {
        every { getString(R.string.account_auth_error_remove_readd) } returns MESSAGE
    }

    @Before
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        AppLog.install(logBuffer)
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `a latched account is stamped with the remove-and-re-add error and reported latched`() = runTest {
        val gate = latchingGate()
        val p = params()
        repeat(THRESHOLD) { gate.onAuthFailure(p) }
        logBuffer.clear() // isolate the helper's breadcrumb from the gate's own latch breadcrumbs
        val dao = mockk<AccountDao>()
        coEvery { dao.setAuthError("acct", MESSAGE) } returns 1

        val latched = markAccountErroredIfLatched(gate, dao, context(), account, p)

        assertTrue(latched, "a latched account is reported so the caller stops syncing it")
        coVerify(exactly = 1) { dao.setAuthError("acct", MESSAGE) }
        val messages = logBuffer.snapshot().map { it.message }
        assertTrue(messages.any { it.contains("account marked errored") }, "a PII-free breadcrumb is logged")
        messages.forEach {
            assertFalse(it.contains("user@yahoo.com"), it)
            assertFalse(it.contains(YAHOO_HOST), it)
        }
    }

    @Test
    fun `an idempotent no-op write is not logged but still reports latched`() = runTest {
        val gate = latchingGate()
        val p = params()
        repeat(THRESHOLD) { gate.onAuthFailure(p) }
        logBuffer.clear() // isolate the helper's (non-)logging from the gate's own latch breadcrumbs
        val dao = mockk<AccountDao>()
        // The conditional UPDATE changed 0 rows (the message is already stored) — no re-log.
        coEvery { dao.setAuthError("acct", MESSAGE) } returns 0

        val latched = markAccountErroredIfLatched(gate, dao, context(), account, p)

        assertTrue(latched)
        assertTrue(logBuffer.snapshot().isEmpty(), "a no-op re-write must not re-log the latch")
    }

    @Test
    fun `a still-ramping account is not errored`() = runTest {
        val gate = latchingGate()
        val p = params()
        gate.onAuthFailure(p) // one failure — below the threshold, still ramping (not latched)
        val dao = mockk<AccountDao>()

        val latched = markAccountErroredIfLatched(gate, dao, context(), account, p)

        assertFalse(latched, "a ramping account keeps retrying, it is not errored")
        coVerify(exactly = 0) { dao.setAuthError(any(), any()) }
    }

    @Test
    fun `a healthy non-gated account is never errored`() = runTest {
        // A disabled policy (non-Yahoo host) never latches, so this is a total no-op.
        val gate = AuthThrottleGate(
            nowMillis = { 0L },
            random = { 0.0 },
            policyForHost = { AuthCadencePolicy.DISABLED },
        )
        val p = params()
        repeat(THRESHOLD * 2) { gate.onAuthFailure(p) }
        val dao = mockk<AccountDao>()

        assertFalse(markAccountErroredIfLatched(gate, dao, context(), account, p))
        coVerify(exactly = 0) { dao.setAuthError(any(), any()) }
    }

    private companion object {
        const val YAHOO_HOST = "imap.mail.yahoo.com"
        const val PORT = 993
        const val THRESHOLD = 4
        const val MESSAGE = "Please remove and re-add this account with valid credentials"
    }
}
