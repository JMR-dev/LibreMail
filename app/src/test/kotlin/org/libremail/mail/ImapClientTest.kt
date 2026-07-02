// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.GreenMailUtil
import com.icegreen.greenmail.util.ServerSetupTest
import jakarta.activation.DataHandler
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
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
}
