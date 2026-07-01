// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.GreenMailUtil
import com.icegreen.greenmail.util.ServerSetupTest
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImapClientTest {

    private lateinit var greenMail: GreenMail
    private val client = ImapClient()

    @Before
    fun setUp() {
        greenMail = GreenMail(ServerSetupTest.SMTP_IMAP)
        greenMail.start()
        greenMail.setUser("alice@example.org", "secret")
    }

    @After
    fun tearDown() {
        greenMail.stop()
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
}
