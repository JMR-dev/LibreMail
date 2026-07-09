// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetupTest
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.domain.model.ImapConnectionParams
import org.libremail.domain.model.MailProvider
import org.libremail.domain.model.MailSecurity
import java.net.ServerSocket
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The issue-#362 auth circuit-breaker's **enforcement** inside [ImapClient], against a real GreenMail
 * IMAP server. Proves the load-bearing distinctions end to end:
 *  - a rejected `LOGIN` (auth failure) **arms** the proactive backoff;
 *  - a transient connect error (connection refused) **does not** — "transient IMAP error ≠ auth backoff";
 *  - while backing off, a subsequent login is **skipped** ([AuthBackoffException]) rather than attempted,
 *    even with a correct credential, so no failed-login storm can reach the provider;
 *  - a successful login leaves (or clears) the backoff.
 *
 * The gate is injected with an always-Yahoo policy and a manual clock, so a GreenMail server on
 * `127.0.0.1` is treated as a gated Yahoo/AOL account and the backoff window is controllable without
 * real sleeps. Both the connect-per-op and connection-reuse code paths funnel through the same guarded
 * `openConnectedStore`, so a reuse-on client is checked too.
 */
class ImapClientAuthBackoffTest {

    private lateinit var greenMail: GreenMail
    private var now = 0L
    private val yahoo = ProviderAuthPolicy.forHost(MailProvider.YAHOO.createAccount("x@yahoo.com").imap.host)
    private val gate = AuthThrottleGate(nowMillis = { now }, random = { 0.0 }, policyForHost = { yahoo })

    private fun client(reuse: Boolean = false) = ImapClient(reuseConnections = reuse, authGate = gate)

    @Before
    fun setUp() {
        greenMail = GreenMail(ServerSetupTest.SMTP_IMAP)
        greenMail.start()
        greenMail.setUser("alice@example.org", "secret")
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        greenMail.stop()
        unmockkAll()
    }

    private fun params(secret: String = "secret", port: Int = greenMail.imap.port) = ImapConnectionParams(
        host = "127.0.0.1",
        port = port,
        security = MailSecurity.NONE,
        username = "alice@example.org",
        secret = secret,
        useXoauth2 = false,
    )

    @Test
    fun `a wrong-password auth failure arms the proactive backoff`() = runTest {
        assertFailsWith<Exception> { client().listFolders(params(secret = "wrong-password")) }

        assertTrue(gate.isAuthBlocked(params()), "a rejected LOGIN must arm the auth circuit-breaker")
    }

    @Test
    fun `a transient connect error does not arm the backoff`() = runTest {
        val closedPort = ServerSocket(0).use { it.localPort } // now free → connection refused, not an auth NO

        assertFailsWith<Exception> { client().listFolders(params(port = closedPort)) }

        assertFalse(
            gate.isAuthBlocked(params(port = closedPort)),
            "a transient (non-auth) connect error must NOT arm the auth backoff",
        )
    }

    @Test
    fun `a successful login leaves the backoff clear`() = runTest {
        client().listFolders(params())

        assertFalse(gate.isAuthBlocked(params()))
    }

    @Test
    fun `while backing off, a login is skipped even with a correct credential`() = runTest {
        // Arm the backoff with one rejected login...
        assertFailsWith<Exception> { client().listFolders(params(secret = "wrong-password")) }
        assertTrue(gate.isAuthBlocked(params()))

        // ...then further logins are SKIPPED (AuthBackoffException from the guard), not attempted — even a
        // correct credential and a different operation, so no failed-login storm reaches the provider.
        assertFailsWith<AuthBackoffException> { client().listFolders(params()) }
        assertFailsWith<AuthBackoffException> { client().fetchRecent(params(), "INBOX", 10) }
    }

    @Test
    fun `the reuse path is gated too`() = runTest {
        // The connection-reuse client establishes its warm socket via the same guarded openConnectedStore.
        assertFailsWith<Exception> { client(reuse = true).listFolders(params(secret = "wrong-password")) }
        assertTrue(gate.isAuthBlocked(params()))
        assertFailsWith<AuthBackoffException> { client(reuse = true).listFolders(params()) }
    }

    @Test
    fun `a login succeeds again once the backoff window elapses`() = runTest {
        assertFailsWith<Exception> { client().listFolders(params(secret = "wrong-password")) }
        now += gate.remainingAuthBlockMillis(params()) // fast-forward past the window

        client().listFolders(params()) // a correct login now goes through and clears the state

        assertFalse(gate.isAuthBlocked(params()))
    }
}
