// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.GreenMailUtil
import com.icegreen.greenmail.util.ServerSetupTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.domain.model.ImapConnectionParams
import org.libremail.domain.model.MailSecurity
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Measures the folder-open round-trip *structure* over a real in-process IMAP server (issue #125),
 * deterministically and without a real network, by routing [ImapClient] through a [CountingImapProxy]
 * that counts the TCP connections and IMAP commands it establishes.
 *
 * The finding these tests pin down: [ImapClient] wraps every operation in its own short-lived
 * [jakarta.mail.Store] (`withStore`), so **each folder-open pays a fresh CONNECT + LOGIN + SELECT +
 * FETCH + LOGOUT** — nothing is reused between operations. On a real network the CONNECT + TLS + LOGIN
 * group is several RTTs of user-perceived latency that a pooled/kept-alive connection would pay only
 * once. See `docs/perf/issue-125-imap-folder-open.md`.
 *
 * These assertions encode the *current* (no-reuse) behaviour. They are also the harness to validate a
 * future connection-reuse fix: when the client reuses one authenticated connection across folder
 * switches, the connection/auth counts here drop below the operation count — flip the expectations to
 * assert reuse and the tests confirm the win against a real IMAP server.
 */
class ImapFolderOpenLatencyTest {

    private lateinit var greenMail: GreenMail
    private lateinit var proxy: CountingImapProxy

    /** Flag OFF (production default): a fresh connect + LOGOUT per operation. */
    private val client = ImapClient()

    /** Flag ON (the spike prototype, issue #125): one kept-alive connection reused across operations. */
    private val reuseClient = ImapClient(reuseConnections = true)

    @Before
    fun setUp() {
        greenMail = GreenMail(ServerSetupTest.SMTP_IMAP)
        greenMail.start()
        greenMail.setUser("alice@example.org", "secret")
        // All IMAP traffic goes through the proxy so we can count it; the proxy forwards to GreenMail.
        proxy = CountingImapProxy(backendHost = "127.0.0.1", backendPort = greenMail.imap.port)
    }

    @After
    fun tearDown() {
        runBlocking { reuseClient.closeReusedConnections() } // release any kept-alive socket before the server stops
        proxy.close()
        greenMail.stop()
    }

    /** Points [ImapClient] at the counting proxy rather than directly at GreenMail. */
    private fun params() = ImapConnectionParams(
        host = "127.0.0.1",
        port = proxy.port,
        security = MailSecurity.NONE,
        username = "alice@example.org",
        secret = "secret",
        useXoauth2 = false,
    )

    private fun seedInbox(count: Int) {
        repeat(count) { i ->
            GreenMailUtil.sendTextEmailTest("alice@example.org", "bob@example.org", "Subject $i", "Body $i")
        }
        greenMail.waitForIncomingEmail(count)
    }

    @Test
    fun `each folder-open establishes a brand-new IMAP connection (no reuse today)`() = runTest {
        seedInbox(2)

        repeat(OPENS) { client.fetchRecent(params(), "INBOX", limit = 50) }

        // One TCP connection per open: nothing is pooled or kept alive across folder-opens. A
        // connection-reuse fix would make this strictly less than OPENS.
        assertEquals(OPENS, proxy.connectionCount, "expected one fresh connection per folder-open")
    }

    @Test
    fun `each folder-open pays a fresh LOGIN and its own SELECT`() = runTest {
        seedInbox(2)

        repeat(OPENS) { client.fetchRecent(params(), "INBOX", limit = 50) }
        proxy.awaitClientStreamsSettled()

        // The avoidable round-trip: a full authentication on every open. Reuse would drop this to 1.
        assertEquals(OPENS, proxy.authCommandCount(), "expected one LOGIN per folder-open")
        // The necessary per-open work: READ_ONLY open issues EXAMINE. Reuse keeps this at one-per-open.
        assertEquals(OPENS, proxy.commandCount("EXAMINE"), "expected one EXAMINE per folder-open")
    }

    @Test
    fun `a single folder-open's round-trip sequence is CONNECT-LOGIN-EXAMINE-FETCH-LOGOUT`() = runTest {
        seedInbox(3)

        client.fetchRecent(params(), "INBOX", limit = 50)
        proxy.awaitClientStreamsSettled()

        assertEquals(1, proxy.connectionCount, "one connection")
        assertEquals(1, proxy.authCommandCount(), "one LOGIN — the connection-setup cost, avoidable on reuse")
        assertEquals(1, proxy.commandCount("EXAMINE"), "one EXAMINE — the necessary per-folder SELECT")
        assertTrue(proxy.commandCount("FETCH") >= 1, "at least one FETCH — the necessary header download")
        assertEquals(1, proxy.commandCount("LOGOUT"), "one LOGOUT — the connection is torn down, not kept alive")
    }

    @Test
    fun `a batch delete opens one connection and pays one LOGIN for the whole selection`() = runTest {
        seedInbox(3)
        val uids = client.fetchRecent(params(), "INBOX", limit = 50).map { it.uid } // the fetch: connection 1
        proxy.awaitClientStreamsSettled()
        val connectionsBefore = proxy.connectionCount
        val loginsBefore = proxy.authCommandCount()

        client.deleteMessages(params(), "INBOX", uids)
        proxy.awaitClientStreamsSettled()

        // Deleting all THREE messages is a single CONNECT + LOGIN + SELECT + STORE + UID EXPUNGE + LOGOUT
        // — one connection and one LOGIN for the whole batch, not the three connects / three LOGINs a
        // per-UID deleteMessage() loop would pay (the #295 N-login inefficiency).
        assertEquals(1, proxy.connectionCount - connectionsBefore, "batch delete must open exactly one connection")
        assertEquals(1, proxy.authCommandCount() - loginsBefore, "batch delete must pay exactly one LOGIN")
    }

    @Test
    fun `opening a folder then reading a message uses two separate connections (compounding cost)`() = runTest {
        seedInbox(1)

        val uid = client.fetchRecent(params(), "INBOX", limit = 50).first().uid // open folder -> connection 1
        client.fetchBodyMarkingSeen(params(), "INBOX", uid) // read a message -> connection 2
        proxy.awaitClientStreamsSettled()

        // No session is shared between listing the folder and reading a message in it: the read pays a
        // second full CONNECT + LOGIN even though it targets the folder we just had open.
        assertEquals(2, proxy.connectionCount, "list + read each open their own connection")
        assertEquals(2, proxy.authCommandCount(), "list + read each pay a full LOGIN")
    }

    // --- Flag ON: the spike prototype reuses one connection across operations (issue #125). ---
    // These are the deterministic proof that reuse works: the SAME real-IMAP operations that cost N
    // connections / N LOGINs above collapse to ONE connection / ONE LOGIN here, with the necessary
    // per-open EXAMINE unchanged. Localhost is ~0 RTT so this proves the STRUCTURE, not wall-clock.

    @Test
    fun `with reuse on, N folder-opens share one connection and one LOGIN`() = runTest {
        seedInbox(2)

        repeat(OPENS) { reuseClient.fetchRecent(params(), "INBOX", limit = 50) }

        // The win: one socket accepted for all OPENS opens (vs. OPENS sockets with the flag off).
        // connectionCount is incremented synchronously on accept, so it needs no stream settling.
        assertEquals(1, proxy.connectionCount, "reuse: a single TCP connection serves every folder-open")

        reuseClient.closeReusedConnections() // evict -> LOGOUT + close, so the proxy's command stream settles
        proxy.awaitClientStreamsSettled()

        assertEquals(1, proxy.authCommandCount(), "reuse: LOGIN paid once, then reused (vs. one per open)")
        assertEquals(OPENS, proxy.commandCount("EXAMINE"), "reuse keeps the necessary one EXAMINE per open")
        assertEquals(1, proxy.commandCount("LOGOUT"), "reuse: one LOGOUT at eviction, not one per open")
    }

    @Test
    fun `with reuse on, opening a folder then reading a message reuses the one connection`() = runTest {
        seedInbox(1)

        val uid = reuseClient.fetchRecent(params(), "INBOX", limit = 50).first().uid // open folder
        reuseClient.fetchBodyMarkingSeen(params(), "INBOX", uid) // read a message in it

        // Contrast with `opening a folder then reading a message uses two separate connections`: the
        // same list-then-read here shares the one kept-alive connection instead of paying a second setup.
        assertEquals(1, proxy.connectionCount, "reuse: list + read share the one connection")

        reuseClient.closeReusedConnections()
        proxy.awaitClientStreamsSettled()

        assertEquals(1, proxy.authCommandCount(), "reuse: one LOGIN covers both the list and the read")
    }

    private companion object {
        const val OPENS = 3
    }
}
