// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetupTest
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.domain.model.ImapConnectionParams
import org.libremail.domain.model.MailSecurity
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the full-history backfill paging primitive ([ImapClient.fetchOlderThan]) against a real
 * in-process IMAP server (issue #12): it pages a mailbox far larger than the 50-message foreground
 * window, and a page sequence RESUMED from a persisted boundary still yields the complete history
 * with no gaps or duplicates.
 */
class ImapClientBackfillTest {

    private lateinit var greenMail: GreenMail
    private val client = ImapClient()

    @Before
    fun setUp() {
        greenMail = GreenMail(ServerSetupTest.SMTP_IMAP)
        greenMail.start()
        greenMail.setUser("alice@example.org", "secret")

        // Every IMAP op now breadcrumbs through AppLog (per-op connect/work timings, issue #358), and
        // android.util.Log is a no-op stub under plain JVM tests. Mock it class-wide — fully qualified so
        // this file still never imports android.util.Log — so no test crashes on the unmocked method.
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

    private fun params() = ImapConnectionParams(
        host = "127.0.0.1",
        port = greenMail.imap.port,
        security = MailSecurity.NONE,
        username = "alice@example.org",
        secret = "secret",
        useXoauth2 = false,
    )

    @Test
    fun `pages a mailbox larger than the foreground window down to the first message`() = runTest {
        appendMessages(TOTAL)

        // The foreground window would only ever cache the newest 50; backfill must reach everything.
        val recent = client.fetchRecent(params(), "INBOX", limit = 50)
        assertEquals(50, recent.size)

        val allUids = pageEntireHistory(pageSize = 50)

        assertEquals(TOTAL, allUids.size, "backfill must page in the whole mailbox, not just 50")
        assertEquals(TOTAL, allUids.toSet().size, "every page must be distinct (no overlap)")
        assertTrue(allUids.size > 50, "backfill caches strictly MORE than the 50-message window")
    }

    @Test
    fun `resumes from a persisted boundary after an interruption without gaps or duplicates`() = runTest {
        appendMessages(TOTAL)

        // First run: page a couple of batches, then "crash" — keep only the persisted boundary UID.
        val firstRun = mutableListOf<Long>()
        var boundary = Long.MAX_VALUE
        repeat(2) {
            val page = client.fetchOlderThan(params(), "INBOX", boundary, PAGE)
            firstRun += page.map { it.uid.toLong() }
            boundary = page.minOf { it.uid.toLong() }
        }
        assertEquals(2 * PAGE, firstRun.size)

        // Second run (fresh process): resume ONLY from the stored boundary and finish the history.
        val resumed = mutableListOf<Long>()
        while (true) {
            val page = client.fetchOlderThan(params(), "INBOX", boundary, PAGE)
            if (page.isEmpty()) break
            resumed += page.map { it.uid.toLong() }
            boundary = page.minOf { it.uid.toLong() }
        }

        val combined = firstRun + resumed
        assertEquals(TOTAL, combined.size, "resume must complete the full history")
        assertEquals(TOTAL, combined.toSet().size, "resume must not re-fetch or skip any message")
    }

    @Test
    fun `returns empty once the oldest message is reached`() = runTest {
        appendMessages(3)
        val oldest = pageEntireHistory(pageSize = 2).min()

        // Nothing exists below the very first UID.
        assertTrue(client.fetchOlderThan(params(), "INBOX", oldest, 50).isEmpty())
    }

    /** Pages the entire mailbox backwards via [ImapClient.fetchOlderThan], returning every UID seen. */
    private suspend fun pageEntireHistory(pageSize: Int): List<Long> {
        val uids = mutableListOf<Long>()
        var boundary = Long.MAX_VALUE
        while (true) {
            val page = client.fetchOlderThan(params(), "INBOX", boundary, pageSize)
            if (page.isEmpty()) break
            uids += page.map { it.uid.toLong() }
            boundary = page.minOf { it.uid.toLong() }
        }
        return uids
    }

    /** Appends [count] distinct messages to INBOX via IMAP (faster than SMTP delivery for bulk). */
    private fun appendMessages(count: Int) {
        val props = Properties().apply {
            put("mail.store.protocol", "imap")
            put("mail.imap.host", "127.0.0.1")
            put("mail.imap.port", greenMail.imap.port.toString())
        }
        val session = Session.getInstance(props)
        val store = session.getStore("imap")
        store.connect("127.0.0.1", greenMail.imap.port, "alice@example.org", "secret")
        try {
            val inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_WRITE)
            val messages = (1..count).map { i ->
                MimeMessage(session).apply {
                    setFrom(InternetAddress("sender$i@example.org"))
                    setRecipient(Message.RecipientType.TO, InternetAddress("alice@example.org"))
                    subject = "Message $i"
                    setText("Body of message $i")
                }
            }.toTypedArray()
            inbox.appendMessages(messages)
            inbox.close(false)
        } finally {
            store.close()
        }
    }

    private companion object {
        const val TOTAL = 120
        const val PAGE = 50
    }
}
