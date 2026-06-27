// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.GreenMailUtil
import com.icegreen.greenmail.util.ServerSetupTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.domain.model.ImapConnectionParams
import org.libremail.domain.model.MailSecurity

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
        assertTrue(client.listFolders(params()).any { it.equals("INBOX", ignoreCase = true) })
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

        val messages = client.fetchRecentInbox(params(), limit = 50)

        assertEquals(2, messages.size)
        assertEquals("Second subject", messages.first().subject)
        assertEquals(setOf("First subject", "Second subject"), messages.map { it.subject }.toSet())
        assertEquals("bob@example.org", messages.first { it.subject == "First subject" }.senderEmail)
    }

    @Test
    fun `fetchBodyMarkingSeen returns the body and marks the message read`() = runTest {
        GreenMailUtil.sendTextEmailTest("alice@example.org", "bob@example.org", "Hello", "The quick brown fox.")
        greenMail.waitForIncomingEmail(1)
        val uid = client.fetchRecentInbox(params(), limit = 50).first().uid

        val content = client.fetchBodyMarkingSeen(params(), uid)

        assertTrue(content.body.contains("quick brown fox"), "body=${content.body}")
        assertTrue(client.fetchRecentInbox(params(), limit = 50).first().isRead, "should be marked read")
    }
}
