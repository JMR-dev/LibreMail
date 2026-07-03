// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import android.content.Context
import android.util.Log
import androidx.work.ListenableWorker.Result
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.data.attachment.AttachmentUriGrants
import org.libremail.data.local.dao.AccountDao
import org.libremail.data.local.dao.OutboxDao
import org.libremail.data.local.entity.AccountEntity
import org.libremail.data.local.entity.OutboxEntity
import org.libremail.data.local.entity.ServerConfigEmbedded
import org.libremail.data.local.toOutgoingAttachmentsJson
import org.libremail.data.security.EncryptedCacheGuard
import org.libremail.domain.model.OutgoingAttachment
import org.libremail.domain.model.SmtpParams
import org.libremail.mail.GraphSendException
import org.libremail.mail.GraphSender
import org.libremail.mail.SendableAttachment
import org.libremail.mail.SmtpSender
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [SendWorker] first gates on [EncryptedCacheGuard] (regression cover for the pre-auth-DB class of
 * bug — while locked it must defer without resolving any of its `Lazy` DB-backed deps), then drains
 * the outbox: sending each queued message over SMTP or Microsoft Graph, deleting it on success,
 * flagging failures for a retry, and — crucially for the "may have sent" case — never auto-retrying
 * a Graph request that might already have delivered.
 */
class SendWorkerTest {

    private val outboxDao = mockk<OutboxDao>(relaxed = true)
    private val accountDao = mockk<AccountDao>()
    private val smtpSender = mockk<SmtpSender>(relaxed = true)
    private val graphSender = mockk<GraphSender>(relaxed = true)
    private val connectionFactory = mockk<MailConnectionFactory>(relaxed = true)
    private val attachmentUriGrants = mockk<AttachmentUriGrants>(relaxed = true)
    private val lazyOutbox = mockk<Lazy<OutboxDao>> { every { get() } returns outboxDao }
    private val lazyAccount = mockk<Lazy<AccountDao>> { every { get() } returns accountDao }
    private val lazyConnection = mockk<Lazy<MailConnectionFactory>> { every { get() } returns connectionFactory }
    private val lazyGrants = mockk<Lazy<AttachmentUriGrants>> { every { get() } returns attachmentUriGrants }
    private val cacheGuard = mockk<EncryptedCacheGuard>()

    private lateinit var cacheDir: File
    private lateinit var appContext: Context

    @Before
    fun setUp() {
        cacheDir = java.nio.file.Files.createTempDirectory("sendworker").toFile()
        appContext = mockk(relaxed = true)
        every { appContext.cacheDir } returns cacheDir
        coEvery { cacheGuard.isCacheLocked() } returns false
    }

    @After
    fun tearDown() {
        cacheDir.deleteRecursively()
        unmockkAll()
    }

    private fun worker() = SendWorker(
        appContext,
        mockk(relaxed = true),
        lazyOutbox,
        lazyAccount,
        smtpSender,
        graphSender,
        lazyConnection,
        cacheGuard,
        lazyGrants,
    )

    private fun account(id: String, authType: String) = AccountEntity(
        id = id,
        email = "$id@example.org",
        displayName = id,
        authType = authType,
        imap = ServerConfigEmbedded("imap.example.org", 993, "SSL_TLS"),
        smtp = ServerConfigEmbedded("smtp.example.org", 465, "SSL_TLS"),
    )

    private fun entity(id: String = "m1", accountId: String = "acct", attachments: String = "") = OutboxEntity(
        id = id,
        accountId = accountId,
        toAddresses = "bob@example.org",
        ccAddresses = "",
        subject = "Hi",
        body = "Body",
        createdAt = 0L,
        attachments = attachments,
    )

    @Test
    fun `retries without resolving any DB dependency when the cache is locked`() = runTest {
        coEvery { cacheGuard.isCacheLocked() } returns true

        assertEquals(Result.retry(), worker().doWork())

        verify(exactly = 0) { lazyOutbox.get() }
        verify(exactly = 0) { lazyAccount.get() }
        verify(exactly = 0) { lazyConnection.get() }
        verify(exactly = 0) { lazyGrants.get() }
    }

    @Test
    fun `an empty outbox succeeds without sending`() = runTest {
        coEvery { outboxDao.getAll() } returns emptyList()

        assertEquals(Result.success(), worker().doWork())

        coVerify(exactly = 1) { outboxDao.getAll() }
        coVerify(exactly = 0) { smtpSender.send(any(), any(), any(), any()) }
    }

    @Test
    fun `drops a queued message whose account was removed`() = runTest {
        val row = entity()
        coEvery { outboxDao.getAll() } returns listOf(row)
        coEvery { accountDao.getById("acct") } returns null

        assertEquals(Result.success(), worker().doWork())

        coVerify { outboxDao.delete("m1") }
        coVerify { attachmentUriGrants.releaseUnreferenced(any()) }
        coVerify(exactly = 0) { smtpSender.send(any(), any(), any(), any()) }
    }

    @Test
    fun `sends a password account message over SMTP then clears it`() = runTest {
        coEvery { outboxDao.getAll() } returns listOf(entity())
        coEvery { accountDao.getById("acct") } returns account("acct", "PASSWORD_IMAP")
        coEvery { connectionFactory.smtpParamsFor(any()) } returns mockk<SmtpParams>()

        assertEquals(Result.success(), worker().doWork())

        coVerify { smtpSender.send(any(), "acct@example.org", any(), any()) }
        coVerify { outboxDao.delete("m1") }
        coVerify { attachmentUriGrants.releaseUnreferenced(any()) }
    }

    @Test
    fun `an SMTP failure flags the row and retries`() = runTest {
        coEvery { outboxDao.getAll() } returns listOf(entity())
        coEvery { accountDao.getById("acct") } returns account("acct", "PASSWORD_IMAP")
        coEvery { connectionFactory.smtpParamsFor(any()) } returns mockk<SmtpParams>()
        coEvery { smtpSender.send(any(), any(), any(), any()) } throws RuntimeException("smtp down")

        assertEquals(Result.retry(), worker().doWork())

        coVerify { outboxDao.setError("m1", "smtp down") }
        coVerify(exactly = 0) { outboxDao.delete(any()) }
    }

    @Test
    fun `sends an Outlook message over Graph`() = runTest {
        coEvery { outboxDao.getAll() } returns listOf(entity())
        coEvery { accountDao.getById("acct") } returns account("acct", "OAUTH_OUTLOOK")
        coEvery { connectionFactory.graphTokenFor(any()) } returns "graph-token"

        assertEquals(Result.success(), worker().doWork())

        coVerify { graphSender.send("graph-token", any(), any()) }
        coVerify { outboxDao.delete("m1") }
        coVerify(exactly = 0) { smtpSender.send(any(), any(), any(), any()) }
    }

    @Test
    fun `a Graph send that may have sent is left queued and not retried`() = runTest {
        coEvery { outboxDao.getAll() } returns listOf(entity())
        coEvery { accountDao.getById("acct") } returns account("acct", "OAUTH_OUTLOOK")
        coEvery { connectionFactory.graphTokenFor(any()) } returns "graph-token"
        coEvery { graphSender.send(any(), any(), any()) } throws
            GraphSendException("maybe sent", mayHaveSent = true)

        // Not a failure: WorkManager must NOT auto-retry, or the message could be duplicated.
        assertEquals(Result.success(), worker().doWork())

        coVerify { outboxDao.setError("m1", match { it.contains("check your Sent folder") }) }
        coVerify(exactly = 0) { outboxDao.delete(any()) }
        coVerify(exactly = 0) { smtpSender.send(any(), any(), any(), any()) } // must not fall back
    }

    @Test
    fun `a Graph rejection falls back to SMTP`() = runTest {
        coEvery { outboxDao.getAll() } returns listOf(entity())
        coEvery { accountDao.getById("acct") } returns account("acct", "OAUTH_OUTLOOK")
        coEvery { connectionFactory.graphTokenFor(any()) } returns "graph-token"
        coEvery { connectionFactory.smtpParamsFor(any()) } returns mockk<SmtpParams>()
        coEvery { graphSender.send(any(), any(), any()) } throws
            GraphSendException("rejected", mayHaveSent = false)

        assertEquals(Result.success(), worker().doWork())

        coVerify { smtpSender.send(any(), "acct@example.org", any(), any()) }
        coVerify { outboxDao.delete("m1") }
    }

    @Test
    fun `a Graph transport error falls back to SMTP`() = runTest {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>(), any<Throwable>()) } returns 0
        coEvery { outboxDao.getAll() } returns listOf(entity())
        coEvery { accountDao.getById("acct") } returns account("acct", "OAUTH_OUTLOOK")
        coEvery { connectionFactory.graphTokenFor(any()) } throws RuntimeException("token refresh failed")
        coEvery { connectionFactory.smtpParamsFor(any()) } returns mockk<SmtpParams>()

        assertEquals(Result.success(), worker().doWork())

        coVerify { smtpSender.send(any(), "acct@example.org", any(), any()) }
        coVerify { outboxDao.delete("m1") }
    }

    @Test
    fun `stages attachments pairing an inline image with its file and content id`() = runTest {
        val attachmentsJson = listOf(
            OutgoingAttachment(uri = "content://1", name = "logo.png", contentId = "logo@x", isInline = true),
            OutgoingAttachment(uri = "content://2", name = "doc.pdf"),
        ).toOutgoingAttachmentsJson()
        stageFile("m1", index = 0, name = "logo.png", bytes = byteArrayOf(1, 2))
        stageFile("m1", index = 1, name = "doc.pdf", bytes = byteArrayOf(3, 4))
        coEvery { outboxDao.getAll() } returns listOf(entity(attachments = attachmentsJson))
        coEvery { accountDao.getById("acct") } returns account("acct", "PASSWORD_IMAP")
        coEvery { connectionFactory.smtpParamsFor(any()) } returns mockk<SmtpParams>()
        val sent = slot<List<SendableAttachment>>()
        coEvery { smtpSender.send(any(), any(), any(), capture(sent)) } returns Unit

        worker().doWork()

        assertEquals(2, sent.captured.size)
        val inline = sent.captured.single { it.isInline }
        assertEquals("logo@x", inline.contentId)
        assertTrue(sent.captured.any { !it.isInline })
    }

    @Test
    fun `falls back to positional staged files when there is no attachment metadata`() = runTest {
        stageFile("m1", index = 0, name = "a.txt", bytes = byteArrayOf(1))
        stageFile("m1", index = 1, name = "b.txt", bytes = byteArrayOf(2))
        coEvery { outboxDao.getAll() } returns listOf(entity(attachments = ""))
        coEvery { accountDao.getById("acct") } returns account("acct", "PASSWORD_IMAP")
        coEvery { connectionFactory.smtpParamsFor(any()) } returns mockk<SmtpParams>()
        val sent = slot<List<SendableAttachment>>()
        coEvery { smtpSender.send(any(), any(), any(), capture(sent)) } returns Unit

        worker().doWork()

        assertEquals(2, sent.captured.size)
        assertFalse(sent.captured.any { it.isInline }) // positional restore is always plain attachments
    }

    /** Stages a file the way the compose pipeline does: cacheDir/outbox/<id>/<index>/<name>. */
    private fun stageFile(messageId: String, index: Int, name: String, bytes: ByteArray) {
        File(cacheDir, "outbox/$messageId/$index").apply { mkdirs() }
            .resolve(name).writeBytes(bytes)
    }
}
