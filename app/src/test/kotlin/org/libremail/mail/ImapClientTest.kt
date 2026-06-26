// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetupTest
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
        greenMail = GreenMail(ServerSetupTest.IMAP)
        greenMail.start()
        greenMail.setUser("alice@example.org", "secret")
    }

    @After
    fun tearDown() {
        greenMail.stop()
    }

    private fun params(secret: String) = ImapConnectionParams(
        host = "127.0.0.1",
        port = greenMail.imap.port,
        security = MailSecurity.NONE,
        username = "alice@example.org",
        secret = secret,
        useXoauth2 = false,
    )

    @Test
    fun `listFolders returns INBOX for a valid login`() = runTest {
        val folders = client.listFolders(params(secret = "secret"))
        assertTrue(folders.any { it.equals("INBOX", ignoreCase = true) }, "folders=$folders")
    }

    @Test
    fun `listFolders fails for a wrong password`() = runTest {
        assertFailsWith<Exception> {
            client.listFolders(params(secret = "wrong-password"))
        }
    }
}
