// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.GreenMailUtil
import com.icegreen.greenmail.util.ServerSetupTest
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.domain.model.ImapConnectionParams
import org.libremail.domain.model.MailSecurity
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Measures the folder-open round-trip *structure* over a real in-process IMAP server (issue #125),
 * deterministically and without a real network, by routing [ImapClient] through a [CountingImapProxy]
 * that counts the TCP connections and IMAP commands it establishes.
 *
 * Two contrasting behaviours are pinned. With reuse **off**, [ImapClient] wraps every operation in its
 * own short-lived [jakarta.mail.Store] (`withStore`), so **each folder-open pays a fresh CONNECT +
 * LOGIN + SELECT + FETCH + LOGOUT** — nothing is reused. With reuse **on** (the production default,
 * issue #357 Part 2), the same real IMAP operations collapse onto **one** kept-alive connection / one
 * LOGIN, with the necessary per-folder EXAMINE unchanged — and a dropped socket is transparently
 * reconnected, an application error is not mistaken for a drop, and an idle socket is evicted. On a
 * real network the CONNECT + TLS + LOGIN group is several RTTs of user-perceived latency (and, on
 * Gmail, the connect *volume* that trips server-side throttling) that reuse pays only once. See
 * `docs/perf/issue-125-imap-folder-open.md`.
 *
 * The reuse-on assertions are also the regression guard: they fail if reuse ever silently regresses to
 * connect-per-operation.
 */
class ImapFolderOpenLatencyTest {

    private lateinit var greenMail: GreenMail
    private lateinit var proxy: CountingImapProxy

    /** Reuse OFF: a fresh connect + LOGOUT per operation — the baseline these counts contrast against. */
    private val client = ImapClient(reuseConnections = false)

    /** Reuse ON (production default, issue #357 Part 2): one kept-alive connection reused across ops. */
    private val reuseClient = ImapClient(reuseConnections = true)

    /**
     * Reuse ON with a zero idle timeout, so `evictIdleReusedConnections()` closes the kept-alive socket
     * immediately — lets the idle-eviction test assert the teardown deterministically without a clock.
     */
    private val evictClient = ImapClient(reuseConnections = true, reuseIdleTimeoutMillis = 0L)

    @Before
    fun setUp() {
        greenMail = GreenMail(ServerSetupTest.SMTP_IMAP)
        greenMail.start()
        greenMail.setUser("alice@example.org", "secret")
        // All IMAP traffic goes through the proxy so we can count it; the proxy forwards to GreenMail.
        proxy = CountingImapProxy(backendHost = "127.0.0.1", backendPort = greenMail.imap.port)

        // Every IMAP op now breadcrumbs through AppLog (per-op connect/work timings, issue #358), and
        // android.util.Log is a no-op stub under plain JVM tests. Mock it class-wide — fully qualified so
        // this file still never imports android.util.Log — so no test crashes on the unmocked method.
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.d(any(), any(), any()) } returns 0 // reuse-stale reconnect logs with a throwable
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>(), any()) } returns 0
    }

    @After
    fun tearDown() {
        // Release any kept-alive socket before the server stops (a no-op for a client that never reused).
        runBlocking {
            reuseClient.closeReusedConnections()
            evictClient.closeReusedConnections()
        }
        proxy.close()
        greenMail.stop()
        unmockkAll()
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

    // --- Reuse ON (production default): one connection is reused across operations (issue #357 Part 2). ---
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

    // --- Hardening: transparent stale recovery, narrow drop detection, and idle eviction. ---

    @Test
    fun `with reuse on, a dropped connection is transparently reconnected on the next op`() = runTest {
        seedInbox(1)

        reuseClient.fetchRecent(params(), "INBOX", limit = 50) // establish the kept-alive connection
        assertEquals(1, proxy.connectionCount, "one connection is established and kept alive")

        // The server (or NAT / a network change) silently drops the idle socket.
        proxy.dropAcceptedConnections()

        // The next op must NOT surface an error: the cache detects the dead socket, reconnects once,
        // and completes the operation, returning its real result.
        val messages = reuseClient.fetchRecent(params(), "INBOX", limit = 50)

        assertEquals(1, messages.size, "the op still returns its result after a transparent reconnect")
        assertEquals(2, proxy.connectionCount, "a dropped reused socket is transparently reconnected (1 -> 2)")
    }

    @Test
    fun `with reuse on, an application error reuses the live connection rather than reconnecting`() = runTest {
        seedInbox(1)

        reuseClient.fetchRecent(params(), "INBOX", limit = 50) // establish the kept-alive connection
        assertEquals(1, proxy.connectionCount)

        // A non-connection error (the UID doesn't exist) must propagate as-is, NOT be mistaken for a
        // dropped socket — so the live connection is neither torn down nor needlessly reconnected, and a
        // mutation would never be silently re-issued over a working socket.
        assertFailsWith<Exception> { reuseClient.fetchBodyMarkingSeen(params(), "INBOX", "999999") }

        assertEquals(1, proxy.connectionCount, "an application error keeps reusing the one live connection")
    }

    @Test
    fun `with reuse on, idle eviction closes the socket and the next op reconnects`() = runTest {
        seedInbox(1)

        evictClient.fetchRecent(params(), "INBOX", limit = 50) // establish the kept-alive connection
        assertEquals(1, proxy.connectionCount)

        // Idle timeout is zero for evictClient, so the sweep closes the just-used socket now.
        evictClient.evictIdleReusedConnections()
        proxy.awaitClientStreamsSettled() // let the LOGOUT + close flush through the proxy

        assertEquals(1, proxy.commandCount("LOGOUT"), "idle eviction tears the socket down with a LOGOUT")

        evictClient.fetchRecent(params(), "INBOX", limit = 50) // must reconnect, the socket is gone
        assertEquals(2, proxy.connectionCount, "the next op after idle eviction reconnects (1 -> 2)")
    }

    private companion object {
        const val OPENS = 3
    }
}
