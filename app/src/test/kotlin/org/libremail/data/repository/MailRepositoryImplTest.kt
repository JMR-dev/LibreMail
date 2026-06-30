// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.repository

import app.cash.turbine.test
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.libremail.data.local.dao.AccountDao
import org.libremail.data.local.dao.AttachmentDao
import org.libremail.data.local.dao.FolderDao
import org.libremail.data.local.dao.MessageDao
import org.libremail.data.local.entity.AccountEntity
import org.libremail.data.local.entity.FolderEntity
import org.libremail.data.local.entity.MessageEntity
import org.libremail.data.local.entity.ServerConfigEmbedded
import org.libremail.data.sync.MailConnectionFactory
import org.libremail.domain.model.FolderRole
import org.libremail.domain.model.ImapConnectionParams
import org.libremail.domain.model.MailSecurity
import org.libremail.mail.ImapClient
import org.libremail.mail.MessageContent

class MailRepositoryImplTest {

    private val messageDao = mockk<MessageDao>()
    private val accountDao = mockk<AccountDao>()
    private val folderDao = mockk<FolderDao>()
    private val attachmentDao = mockk<AttachmentDao>(relaxed = true)
    private val imapClient = mockk<ImapClient>()
    private val connectionFactory = mockk<MailConnectionFactory>()
    private val repository = MailRepositoryImpl(
        context = mockk(),
        messageDao = messageDao,
        accountDao = accountDao,
        attachmentDao = attachmentDao,
        outboxDao = mockk(),
        draftDao = mockk(),
        folderDao = folderDao,
        imapClient = imapClient,
        connectionFactory = connectionFactory,
        sendScheduler = mockk(),
    )

    @Test
    fun `observeMessages is empty when the cache is empty`() = runTest {
        every { messageDao.observeAll() } returns flowOf(emptyList())
        repository.observeMessages().test {
            assertTrue(awaitItem().isEmpty())
            awaitComplete()
        }
    }

    @Test
    fun `observeMessages maps cached entities`() = runTest {
        val entity = messageEntity("1", "INBOX")
        every { messageDao.observeAll() } returns flowOf(listOf(entity))
        repository.observeMessages().test {
            val items = awaitItem()
            assertEquals(1, items.size)
            assertEquals("Ada", items.first().sender)
            assertEquals("INBOX", items.first().folder)
            awaitComplete()
        }
    }

    @Test
    fun `observeFolders maps cached folders with their roles`() = runTest {
        every { folderDao.observeForAccount("acct") } returns flowOf(
            listOf(FolderEntity("acct", "[Gmail]/Sent Mail", "Sent Mail", "SENT", selectable = true, sortOrder = 1)),
        )
        repository.observeFolders("acct").test {
            val folders = awaitItem()
            assertEquals(1, folders.size)
            assertEquals(FolderRole.SENT, folders.first().role)
            assertEquals("Sent Mail", folders.first().displayName)
            awaitComplete()
        }
    }

    @Test
    fun `openMessage fetches the body from the message's own folder, not the inbox`() = runTest {
        val id = "acct:Archive:5"
        coEvery { messageDao.getById(id) } returns messageEntity(id, "Archive")
        coEvery { accountDao.getById("acct") } returns accountEntity()
        coEvery { connectionFactory.imapParamsFor(any()) } returns imapParams()
        coEvery { imapClient.fetchBodyMarkingSeen(any(), "Archive", "5") } returns MessageContent("Body text", isHtml = false)
        coEvery { messageDao.updateBody(id, any(), any(), any()) } just Runs
        coEvery { messageDao.setRead(id, true) } just Runs

        repository.openMessage(id)

        // The UID (5) must be resolved against the message's folder (Archive), since IMAP UIDs are
        // unique only within a folder.
        coVerify { imapClient.fetchBodyMarkingSeen(any(), "Archive", "5") }
    }

    private fun messageEntity(id: String, folder: String) = MessageEntity(
        id = id,
        accountId = "acct",
        sender = "Ada",
        senderEmail = "ada@example.org",
        subject = "Hi",
        snippet = "snippet",
        body = "",
        timestampMillis = 1_000L,
        isRead = false,
        isStarred = false,
        folder = folder,
        bodyFetched = false,
    )

    private fun accountEntity() = AccountEntity(
        id = "acct",
        email = "ada@example.org",
        displayName = "Ada",
        authType = "PASSWORD_IMAP",
        imap = ServerConfigEmbedded("imap.example.org", 993, "SSL_TLS"),
        smtp = ServerConfigEmbedded("smtp.example.org", 465, "SSL_TLS"),
    )

    private fun imapParams() = ImapConnectionParams(
        host = "imap.example.org",
        port = 993,
        security = MailSecurity.SSL_TLS,
        username = "ada@example.org",
        secret = "secret",
        useXoauth2 = false,
    )
}
