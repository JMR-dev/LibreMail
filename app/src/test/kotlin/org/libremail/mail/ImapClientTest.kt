// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.GreenMailUtil
import com.icegreen.greenmail.util.ServerSetupTest
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import jakarta.activation.DataHandler
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.domain.model.ImapConnectionParams
import org.libremail.domain.model.MailSecurity
import org.libremail.reporting.AppLog
import org.libremail.reporting.RingLogBuffer
import java.util.Properties
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImapClientTest {

    private lateinit var greenMail: GreenMail

    // Reuse off: this suite pins the connect-per-operation IMAP behaviour it was written against. The
    // connection-reuse path (production default) has its own coverage in ImapFolderOpenLatencyTest.
    private val client = ImapClient(reuseConnections = false)

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

    private fun params(secret: String = "secret") = ImapConnectionParams(
        host = "127.0.0.1",
        port = greenMail.imap.port,
        security = MailSecurity.NONE,
        username = "alice@example.org",
        secret = secret,
        useXoauth2 = false,
    )

    @Test
    fun `listFolders returns INBOX for a valid login`() = runTest {
        assertTrue(client.listFolders(params()).any { it.fullName.equals("INBOX", ignoreCase = true) })
    }

    @Test
    fun `listFolders fails for a wrong password`() = runTest {
        assertFailsWith<Exception> { client.listFolders(params(secret = "wrong-password")) }
    }

    @Test
    fun `fetchRecentInbox returns delivered messages newest first`() = runTest {
        GreenMailUtil.sendTextEmailTest("alice@example.org", "bob@example.org", "First subject", "Body one")
        GreenMailUtil.sendTextEmailTest("alice@example.org", "carol@example.org", "Second subject", "Body two")
        greenMail.waitForIncomingEmail(2)

        val messages = client.fetchRecent(params(), "INBOX", limit = 50)

        assertEquals(2, messages.size)
        assertEquals("Second subject", messages.first().subject)
        assertEquals(setOf("First subject", "Second subject"), messages.map { it.subject }.toSet())
        assertEquals("bob@example.org", messages.first { it.subject == "First subject" }.senderEmail)
    }

    @Test
    fun `fetchBodyMarkingSeen returns the body and marks the message read`() = runTest {
        GreenMailUtil.sendTextEmailTest("alice@example.org", "bob@example.org", "Hello", "The quick brown fox.")
        greenMail.waitForIncomingEmail(1)
        val uid = client.fetchRecent(params(), "INBOX", limit = 50).first().uid

        val content = client.fetchBodyMarkingSeen(params(), "INBOX", uid)

        assertTrue(content.body.contains("quick brown fox"), "body=${content.body}")
        assertTrue(client.fetchRecent(params(), "INBOX", limit = 50).first().isRead, "should be marked read")
    }

    @Test
    fun `fetchBodyMarkingSeen breadcrumbs connect and phase timings without leaking PII`() = runTest {
        GreenMailUtil.sendTextEmailTest("alice@example.org", "bob@example.org", "Secret subject", "Body.")
        greenMail.waitForIncomingEmail(1)
        val uid = client.fetchRecent(params(), "INBOX", limit = 50).first().uid
        val buffer = RingLogBuffer()
        AppLog.install(buffer)

        client.fetchBodyMarkingSeen(params(), "INBOX", uid)

        val messages = buffer.snapshot().map { it.message }
        // withStore labels the op and splits connect (CONNECT+TLS+LOGIN) from work — timings only.
        assertTrue(
            messages.any { it.startsWith("body-fetch connect=") && it.contains("work=") && it.contains("live=") },
            "messages=$messages",
        )
        // fetchBodyMarkingSeen adds the select/body/flag phase split plus PII-free size counts.
        assertTrue(
            messages.any { it.startsWith("body-fetch select=") && it.contains("rfc822=") && it.contains("att=") },
            "messages=$messages",
        )
        // Breadcrumbs are numbers and fixed labels only — never the subject, sender, or recipient.
        messages.forEach { message ->
            assertFalse(message.contains("Secret subject"), message)
            assertFalse(message.contains("bob@example.org"), message)
            assertFalse(message.contains("alice@example.org"), message)
        }
    }

    @Test
    fun `search returns only messages matching the query`() = runTest {
        GreenMailUtil.sendTextEmailTest("alice@example.org", "bob@example.org", "Vacation plans", "Beach trip")
        GreenMailUtil.sendTextEmailTest("alice@example.org", "carol@example.org", "Invoice 42", "Payment due")
        greenMail.waitForIncomingEmail(2)

        val results = client.search(params(), "INBOX", query = "Vacation", limit = 50)

        assertEquals(1, results.size)
        assertEquals("Vacation plans", results.first().subject)
    }

    @Test
    fun `listFolders includes a created non-inbox folder`() = runTest {
        appendMessage("Archive", "bob@example.org", "Archived", "Stored away")

        val names = client.listFolders(params()).map { it.fullName }

        assertTrue(names.any { it.equals("INBOX", ignoreCase = true) })
        assertTrue(names.any { it.equals("Archive", ignoreCase = true) }, "folders=$names")
    }

    @Test
    fun `moveMessages relocates a message to another folder`() = runTest {
        GreenMailUtil.sendTextEmailTest("alice@example.org", "bob@example.org", "Move me", "Relocate this")
        greenMail.waitForIncomingEmail(1)
        appendMessage("Archive", "carol@example.org", "Seed", "Creates the Archive folder")
        val uid = client.fetchRecent(params(), "INBOX", limit = 50).first { it.subject == "Move me" }.uid

        client.moveMessages(params(), "INBOX", listOf(uid), "Archive")

        val inbox = client.fetchRecent(params(), "INBOX", limit = 50).map { it.subject }
        val archive = client.fetchRecent(params(), "Archive", limit = 50).map { it.subject }
        assertFalse(inbox.contains("Move me"), "inbox=$inbox")
        assertTrue(archive.contains("Move me"), "archive=$archive")
    }

    @Test
    fun `fetchForReply returns recipients and body without marking the message read`() = runTest {
        GreenMailUtil.sendTextEmailTest("alice@example.org", "bob@example.org", "Question", "What time?")
        greenMail.waitForIncomingEmail(1)
        val uid = client.fetchRecent(params(), "INBOX", limit = 50).first().uid

        val context = client.fetchForReply(params(), "INBOX", uid)

        assertEquals("bob@example.org", context.fromEmail)
        assertTrue(context.toRecipients.contains("alice@example.org"), "to=${context.toRecipients}")
        assertTrue(context.body.contains("What time?"), "body=${context.body}")
        assertFalse(client.fetchRecent(params(), "INBOX", limit = 50).first().isRead, "should stay unread")
    }

    @Test
    fun `fetchBodyPeek returns body and attachments without marking the message read`() = runTest {
        GreenMailUtil.sendTextEmailTest("alice@example.org", "bob@example.org", "Peek", "Peek body text")
        greenMail.waitForIncomingEmail(1)
        val uid = client.fetchRecent(params(), "INBOX", limit = 50).first().uid

        val content = client.fetchBodyPeek(params(), "INBOX", uid)

        assertTrue(content.body.contains("Peek body text"), "body=${content.body}")
        assertFalse(client.fetchRecent(params(), "INBOX", limit = 50).first().isRead, "should stay unread")
    }

    @Test
    fun `fetchBodyPeek splits inline cid images from real attachments`() = runTest {
        // A digest-style message: multipart/related(html + inline image) alongside a real attachment.
        appendInlineImageDigest()
        val uid = client.fetchRecent(params(), "INBOX", limit = 50).first().uid

        val content = client.fetchBodyPeek(params(), "INBOX", uid)

        assertTrue(content.isHtml, "the html body must be chosen")
        assertTrue(content.body.contains("cid:logo1"), "body=${content.body}")
        // Both parts are collected (so the inline image's bytes are fetchable by index), but only the
        // inline one carries a Content-ID — the reader filters on that to keep it out of the list.
        assertEquals(2, content.attachments.size, "attachments=${content.attachments}")
        val inline = content.attachments.single { it.contentId != null }
        assertEquals("logo1", inline.contentId)
        assertEquals("logo.png", inline.filename)
        assertTrue(inline.mimeType.equals("image/png", ignoreCase = true), "mime=${inline.mimeType}")
        val attachment = content.attachments.single { it.contentId == null }
        assertEquals("invoice.pdf", attachment.filename)
    }

    @Test
    fun `fetchRecent reads a non-inbox folder isolated from the inbox`() = runTest {
        GreenMailUtil.sendTextEmailTest("alice@example.org", "bob@example.org", "Inbox subject", "In the inbox")
        greenMail.waitForIncomingEmail(1)
        appendMessage("Archive", "carol@example.org", "Archived subject", "In the archive")

        val archive = client.fetchRecent(params(), "Archive", limit = 50)
        assertEquals(1, archive.size)
        assertEquals("Archived subject", archive.first().subject)

        // The archived message must not leak into the inbox (UIDs are per-folder).
        val inbox = client.fetchRecent(params(), "INBOX", limit = 50)
        assertEquals(setOf("Inbox subject"), inbox.map { it.subject }.toSet())
    }

    @Test
    fun `fetchAttachment downloads a part's bytes by its index`() = runTest {
        appendInlineImageDigest() // part 0 = inline logo.png, part 1 = invoice.pdf
        val uid = client.fetchRecent(params(), "INBOX", limit = 50).first().uid

        val inline = client.fetchAttachment(params(), "INBOX", uid, 0)
        val attachment = client.fetchAttachment(params(), "INBOX", uid, 1)

        assertEquals("logo.png", inline.filename)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), inline.bytes)
        assertEquals("invoice.pdf", attachment.filename)
        assertContentEquals(byteArrayOf(5, 6, 7), attachment.bytes)
        assertTrue(attachment.mimeType.contains("pdf", ignoreCase = true), attachment.mimeType)
    }

    @Test
    fun `fetchAttachment fails for an out-of-range part index`() = runTest {
        appendInlineImageDigest()
        val uid = client.fetchRecent(params(), "INBOX", limit = 50).first().uid

        assertFailsWith<Exception> { client.fetchAttachment(params(), "INBOX", uid, 99) }
    }

    @Test
    fun `fetchAttachment fails when the message is not found`() = runTest {
        GreenMailUtil.sendTextEmailTest("alice@example.org", "bob@example.org", "Solo", "Body")
        greenMail.waitForIncomingEmail(1)

        assertFailsWith<Exception> { client.fetchAttachment(params(), "INBOX", "999999", 0) }
    }

    @Test
    fun `setFlag marks a message flagged on the server`() = runTest {
        GreenMailUtil.sendTextEmailTest("alice@example.org", "bob@example.org", "Star me", "Body")
        greenMail.waitForIncomingEmail(1)
        val uid = client.fetchRecent(params(), "INBOX", limit = 50).first().uid

        client.setFlag(params(), "INBOX", uid, Flags.Flag.FLAGGED, value = true)

        assertTrue(client.fetchRecent(params(), "INBOX", limit = 50).first().isFlagged, "should be flagged")
    }

    @Test
    fun `setFlag on an unknown uid is a no-op`() = runTest {
        GreenMailUtil.sendTextEmailTest("alice@example.org", "bob@example.org", "Present", "Body")
        greenMail.waitForIncomingEmail(1)

        // No message has this uid, so getMessageByUID returns null and setFlag simply does nothing.
        client.setFlag(params(), "INBOX", "999999", Flags.Flag.FLAGGED, value = true)

        assertFalse(client.fetchRecent(params(), "INBOX", limit = 50).first().isFlagged)
    }

    @Test
    fun `deleteMessage expunges the message from the folder`() = runTest {
        GreenMailUtil.sendTextEmailTest("alice@example.org", "bob@example.org", "Delete me", "Body")
        GreenMailUtil.sendTextEmailTest("alice@example.org", "bob@example.org", "Keep me", "Body")
        greenMail.waitForIncomingEmail(2)
        val uid = client.fetchRecent(params(), "INBOX", limit = 50).first { it.subject == "Delete me" }.uid

        client.deleteMessage(params(), "INBOX", uid)

        val remaining = client.fetchRecent(params(), "INBOX", limit = 50).map { it.subject }
        assertFalse(remaining.contains("Delete me"), "remaining=$remaining")
        assertTrue(remaining.contains("Keep me"), "remaining=$remaining")
    }

    @Test
    fun `deleteMessages expunges only the given uids and spares other Deleted-flagged mail`() = runTest {
        GreenMailUtil.sendTextEmailTest("alice@example.org", "bob@example.org", "Target", "delete this")
        GreenMailUtil.sendTextEmailTest("alice@example.org", "bob@example.org", "Bystander", "flagged elsewhere")
        GreenMailUtil.sendTextEmailTest("alice@example.org", "bob@example.org", "Untouched", "no flag at all")
        greenMail.waitForIncomingEmail(3)
        val bySubject = client.fetchRecent(params(), "INBOX", limit = 50).associateBy { it.subject }

        // Simulate a second client (or a partial earlier move): "Bystander" is flagged \Deleted but has
        // NOT been expunged — setFlag closes the folder with expunge=false, so the flag persists.
        client.setFlag(params(), "INBOX", bySubject.getValue("Bystander").uid, Flags.Flag.DELETED, value = true)

        // Delete only "Target". The old untargeted mailbox.expunge() would ALSO permanently drop the
        // \Deleted "Bystander"; a targeted UID EXPUNGE removes just the target (issue #295).
        client.deleteMessages(params(), "INBOX", listOf(bySubject.getValue("Target").uid))

        val remaining = client.fetchRecent(params(), "INBOX", limit = 50).map { it.subject }
        assertFalse(remaining.contains("Target"), "the targeted message must be expunged, remaining=$remaining")
        assertTrue(remaining.contains("Bystander"), "an unrelated \\Deleted message must survive, remaining=$remaining")
        assertTrue(remaining.contains("Untouched"), "an unflagged message must survive, remaining=$remaining")
    }

    @Test
    fun `deleteMessages removes every uid in the batch`() = runTest {
        listOf("A", "B", "C", "D").forEach {
            GreenMailUtil.sendTextEmailTest("alice@example.org", "bob@example.org", it, "Body $it")
        }
        greenMail.waitForIncomingEmail(4)
        val bySubject = client.fetchRecent(params(), "INBOX", limit = 50).associateBy { it.subject }

        client.deleteMessages(params(), "INBOX", listOf(bySubject.getValue("A").uid, bySubject.getValue("C").uid))

        val remaining = client.fetchRecent(params(), "INBOX", limit = 50).map { it.subject }.toSet()
        assertEquals(setOf("B", "D"), remaining, "remaining=$remaining")
    }

    @Test
    fun `moveMessages expunges only the moved uids and spares other Deleted-flagged mail`() = runTest {
        GreenMailUtil.sendTextEmailTest("alice@example.org", "bob@example.org", "Move me", "relocate this")
        GreenMailUtil.sendTextEmailTest("alice@example.org", "bob@example.org", "Bystander", "flagged elsewhere")
        greenMail.waitForIncomingEmail(2)
        appendMessage("Archive", "carol@example.org", "Seed", "Creates the Archive folder")
        val bySubject = client.fetchRecent(params(), "INBOX", limit = 50).associateBy { it.subject }
        // "Bystander" is flagged \Deleted (by another client) but left in the source folder, un-expunged.
        client.setFlag(params(), "INBOX", bySubject.getValue("Bystander").uid, Flags.Flag.DELETED, value = true)

        client.moveMessages(params(), "INBOX", listOf(bySubject.getValue("Move me").uid), "Archive")

        val inbox = client.fetchRecent(params(), "INBOX", limit = 50).map { it.subject }
        assertFalse(inbox.contains("Move me"), "the moved message must leave the source, inbox=$inbox")
        // The move's expunge must be targeted: an unrelated \Deleted message must NOT be dropped (#295).
        assertTrue(inbox.contains("Bystander"), "an unrelated \\Deleted message must survive the move, inbox=$inbox")
        val archive = client.fetchRecent(params(), "Archive", limit = 50).map { it.subject }
        assertTrue(archive.contains("Move me"), "archive=$archive")
    }

    @Test
    fun `fetchRecent returns empty for an empty folder`() = runTest {
        createFolder("Empty")

        assertTrue(client.fetchRecent(params(), "Empty", limit = 50).isEmpty())
    }

    @Test
    fun `body and reply fetches fail for an unknown uid`() = runTest {
        GreenMailUtil.sendTextEmailTest("alice@example.org", "bob@example.org", "Present", "Body")
        greenMail.waitForIncomingEmail(1)

        assertFailsWith<Exception> { client.fetchBodyMarkingSeen(params(), "INBOX", "999999") }
        assertFailsWith<Exception> { client.fetchBodyPeek(params(), "INBOX", "999999") }
        assertFailsWith<Exception> { client.fetchForReply(params(), "INBOX", "999999") }
    }

    @Test
    fun `STARTTLS and XOAUTH2 params drive the corresponding connection properties`() = runTest {
        // GreenMail here is plaintext with no XOAUTH2, so the connect fails — but buildProps has already
        // run, which is the STARTTLS + XOAUTH2 property wiring this exercises.
        val params = ImapConnectionParams(
            host = "127.0.0.1",
            port = greenMail.imap.port,
            security = MailSecurity.STARTTLS,
            username = "alice@example.org",
            secret = "secret",
            useXoauth2 = true,
            strictStartTls = true,
        )

        assertFailsWith<Exception> { client.listFolders(params) }
    }

    @Test
    fun `idle syncs once on connect, again on newly delivered mail, and stops on cancel`() = runBlocking {
        // idle() logs connect/push breadcrumbs via AppLog, which forwards to Logcat; stub the Android
        // stub (by fully-qualified name, so this file never imports android.util.Log — only AppLog.kt
        // may) so the JVM test doesn't crash on the unmocked method, and install a real buffer so the
        // breadcrumbs can be asserted directly instead of via a Log verification.
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        val buffer = RingLogBuffer()
        AppLog.install(buffer)
        val activity = Channel<Unit>(Channel.UNLIMITED)
        val job = launch(Dispatchers.IO) { client.idle(params()) { activity.send(Unit) } }
        try {
            // A sync fires immediately on connect to catch anything already waiting...
            withTimeout(IDLE_TIMEOUT_MS) { activity.receive() }
            // ...then an IMAP IDLE push fires another when new mail arrives.
            GreenMailUtil.sendTextEmailTest("alice@example.org", "bob@example.org", "Pushed", "Body")
            withTimeout(IDLE_TIMEOUT_MS) { activity.receive() }
        } finally {
            job.cancelAndJoin() // cancelling closes the connection and unblocks idle()
        }
        assertTrue(job.isCompleted, "the idle loop must terminate on cancellation")

        val messages = buffer.snapshot().map { it.message }
        assertTrue(messages.contains("IDLE connected"), "messages=$messages")
        assertTrue(messages.any { it == "IDLE push: 1 new message(s)" }, "messages=$messages")
        // idle() only holds host/username via ImapConnectionParams and must stay account-agnostic —
        // attribution belongs to the IdleService caller (accountLogRef) — regression guard for #297.
        val connectionParams = params()
        messages.forEach { message ->
            assertFalse(message.contains(connectionParams.host), message)
            assertFalse(message.contains(connectionParams.username), message)
        }
    }

    /** Creates [folderName] (holding messages) if it does not already exist. */
    private fun createFolder(folderName: String) {
        val props = Properties().apply {
            put("mail.store.protocol", "imap")
            put("mail.imap.host", "127.0.0.1")
            put("mail.imap.port", greenMail.imap.port.toString())
        }
        val store = Session.getInstance(props).getStore("imap")
        store.connect("127.0.0.1", greenMail.imap.port, "alice@example.org", "secret")
        try {
            val folder = store.getFolder(folderName)
            if (!folder.exists()) folder.create(Folder.HOLDS_MESSAGES)
        } finally {
            store.close()
        }
    }

    /**
     * Appends a rich digest to the INBOX: a `multipart/mixed` of a `multipart/related` (HTML body
     * referencing an inline image via `cid:logo1`) plus a genuine PDF attachment — the shape that
     * regressed inline images into the attachment list (issue #133).
     */
    private fun appendInlineImageDigest() {
        val props = Properties().apply {
            put("mail.store.protocol", "imap")
            put("mail.imap.host", "127.0.0.1")
            put("mail.imap.port", greenMail.imap.port.toString())
        }
        val session = Session.getInstance(props)

        val htmlPart = MimeBodyPart().apply {
            setContent("<html><body><img src=\"cid:logo1\"><p>Hello</p></body></html>", "text/html; charset=utf-8")
        }
        val inlineImage = MimeBodyPart().apply {
            dataHandler = DataHandler(ByteArrayDataSource(byteArrayOf(1, 2, 3, 4), "image/png"))
            contentID = "<logo1>"
            disposition = Part.INLINE
            fileName = "logo.png"
        }
        val related = MimeBodyPart().apply {
            setContent(
                MimeMultipart("related").apply {
                    addBodyPart(htmlPart)
                    addBodyPart(inlineImage)
                },
            )
        }
        val attachment = MimeBodyPart().apply {
            dataHandler = DataHandler(ByteArrayDataSource(byteArrayOf(5, 6, 7), "application/pdf"))
            disposition = Part.ATTACHMENT
            fileName = "invoice.pdf"
        }
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress("bob@example.org"))
            setRecipient(Message.RecipientType.TO, InternetAddress("alice@example.org"))
            subject = "Daily Digest"
            setContent(
                MimeMultipart("mixed").apply {
                    addBodyPart(related)
                    addBodyPart(attachment)
                },
            )
            // Flush each part's Content-Type/Content-ID/Content-Disposition into headers so the
            // appended raw MIME round-trips them (without this the Content-ID is dropped).
            saveChanges()
        }

        val store = session.getStore("imap")
        store.connect("127.0.0.1", greenMail.imap.port, "alice@example.org", "secret")
        try {
            val inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_WRITE)
            inbox.appendMessages(arrayOf(message))
            inbox.close(false)
        } finally {
            store.close()
        }
    }

    /** Creates [folderName] if needed and appends a message to it, via Jakarta Mail directly. */
    private fun appendMessage(folderName: String, from: String, subject: String, body: String) {
        val props = Properties().apply {
            put("mail.store.protocol", "imap")
            put("mail.imap.host", "127.0.0.1")
            put("mail.imap.port", greenMail.imap.port.toString())
        }
        val session = Session.getInstance(props)
        val store = session.getStore("imap")
        store.connect("127.0.0.1", greenMail.imap.port, "alice@example.org", "secret")
        try {
            val folder = store.getFolder(folderName)
            if (!folder.exists()) folder.create(Folder.HOLDS_MESSAGES)
            folder.open(Folder.READ_WRITE)
            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(from))
                setRecipient(Message.RecipientType.TO, InternetAddress("alice@example.org"))
                this.subject = subject
                setText(body)
            }
            folder.appendMessages(arrayOf(message))
            folder.close(false)
        } finally {
            store.close()
        }
    }

    private companion object {
        /** Generous ceiling for the in-process IDLE round trip so the assertion never races the server. */
        const val IDLE_TIMEOUT_MS = 15_000L
    }
}
