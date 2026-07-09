// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import android.content.Context
import androidx.work.ListenableWorker.Result
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetupTest
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
import org.libremail.data.local.CacheEncryptionUnavailableException
import org.libremail.data.local.dao.AccountDao
import org.libremail.data.local.dao.OutboxDao
import org.libremail.data.local.entity.AccountEntity
import org.libremail.data.local.entity.OutboxEntity
import org.libremail.data.local.entity.ServerConfigEmbedded
import org.libremail.data.local.toOutgoingAttachmentsJson
import org.libremail.data.security.EncryptedCacheGuard
import org.libremail.domain.model.MailSecurity
import org.libremail.domain.model.OutgoingAttachment
import org.libremail.domain.model.SmtpParams
import org.libremail.mail.GraphSendException
import org.libremail.mail.GraphSender
import org.libremail.mail.SendableAttachment
import org.libremail.mail.SmtpSender
import org.libremail.reporting.AppLog
import org.libremail.reporting.RingLogBuffer
import org.libremail.reporting.accountLogRef
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [SendWorker] first gates on [EncryptedCacheGuard] (regression cover for the pre-auth-DB class of
 * bug — while locked it must defer without resolving any of its `Lazy` DB-backed deps), then drains
 * the outbox: sending each queued message over SMTP or Microsoft Graph, deleting it on success,
 * flagging failures for a retry, and — crucially for the "may have sent" case — never auto-retrying
 * a Graph request that might already have delivered.
 *
 * It also logs breadcrumbs via [AppLog] (outbox-drain size, per-message send result, the Graph→SMTP
 * fallback) — asserted here against a real [RingLogBuffer] rather than a mocked `Log`, per #328. Those
 * assertions double as the regression cover for #297: `account.email` must never reach a log line,
 * only the non-PII [accountLogRef].
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
    private val logBuffer = RingLogBuffer()

    private lateinit var cacheDir: File
    private lateinit var appContext: Context

    @Before
    fun setUp() {
        cacheDir = java.nio.file.Files.createTempDirectory("sendworker").toFile()
        appContext = mockk(relaxed = true)
        every { appContext.cacheDir } returns cacheDir
        coEvery { cacheGuard.isCacheLocked() } returns false
        // AppLog forwards every call to Logcat; stub the Android stub (by fully-qualified name, so
        // this file — like the production code it exercises — never imports android.util.Log; only
        // AppLog.kt may) so a JVM unit test doesn't crash on the unmocked method, and install a real
        // buffer so the breadcrumb + #297 no-PII assertions below read actual recorded lines rather
        // than a `Log` verification.
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>(), any()) } returns 0
        AppLog.install(logBuffer)
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
    fun `defers with a retry when the encrypted cache is unavailable, without crashing`() = runTest {
        // Cache unlocked (setUp), so the worker resolves its Lazy DB deps and reads the outbox; that first
        // DB access (Room's deferred cache open) throws because SQLCipher's native library is unavailable
        // on this device (issue #359). It is OUTSIDE any per-message runCatching, so without the guard it
        // would escape doWork — the guard turns it into a soft retry with a PII-free breadcrumb.
        coEvery { outboxDao.getAll() } throws
            CacheEncryptionUnavailableException(UnsatisfiedLinkError("SQLiteConnection.nativeOpen"))

        assertEquals(Result.retry(), worker().doWork())

        val messages = logBuffer.snapshot().map { it.message }
        assertTrue(messages.any { it.startsWith("deferred: encrypted cache unavailable") }, "messages=$messages")
    }

    @Test
    fun `an empty outbox succeeds without sending`() = runTest {
        coEvery { outboxDao.getAll() } returns emptyList()

        assertEquals(Result.success(), worker().doWork())

        coVerify(exactly = 1) { outboxDao.getAll() }
        coVerify(exactly = 0) { smtpSender.send(any(), any(), any(), any()) }
    }

    @Test
    fun `doWork logs the outbox-drain breadcrumb with the queued count`() = runTest {
        coEvery { outboxDao.getAll() } returns listOf(entity())
        coEvery { accountDao.getById("acct") } returns account("acct", "PASSWORD_IMAP")
        coEvery { connectionFactory.smtpParamsFor(any()) } returns mockk<SmtpParams>()

        worker().doWork()

        assertTrue(logBuffer.snapshot().map { it.message }.contains("outbox drain: 1 queued"))
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
    fun `sends a password account message over SMTP then clears it, logging a PII-free breadcrumb`() = runTest {
        coEvery { outboxDao.getAll() } returns listOf(entity())
        coEvery { accountDao.getById("acct") } returns account("acct", "PASSWORD_IMAP")
        coEvery { connectionFactory.smtpParamsFor(any()) } returns mockk<SmtpParams>()

        assertEquals(Result.success(), worker().doWork())

        coVerify { smtpSender.send(any(), "acct@example.org", any(), any()) }
        coVerify { outboxDao.delete("m1") }
        coVerify { attachmentUriGrants.releaseUnreferenced(any()) }
        val messages = logBuffer.snapshot().map { it.message }
        assertTrue(messages.contains("sent ${accountLogRef("acct")} via SMTP"), "messages=$messages")
        messages.forEach { assertFalse(it.contains("acct@example.org"), it) }
    }

    @Test
    fun `an SMTP failure flags the row, retries, and logs a PII-free failure breadcrumb`() = runTest {
        coEvery { outboxDao.getAll() } returns listOf(entity())
        coEvery { accountDao.getById("acct") } returns account("acct", "PASSWORD_IMAP")
        coEvery { connectionFactory.smtpParamsFor(any()) } returns mockk<SmtpParams>()
        coEvery { smtpSender.send(any(), any(), any(), any()) } throws RuntimeException("smtp down")

        assertEquals(Result.retry(), worker().doWork())

        coVerify { outboxDao.setError("m1", "smtp down") }
        coVerify(exactly = 0) { outboxDao.delete(any()) }
        val messages = logBuffer.snapshot().map { it.message }
        assertTrue(messages.contains("send failed for ${accountLogRef("acct")}; will retry"), "messages=$messages")
        messages.forEach { assertFalse(it.contains("acct@example.org"), it) }
    }

    // --- issue #363: iCloud message-size cap -----------------------------------------------------

    private fun icloudAccount(id: String = "acct") =
        account(id, "PASSWORD_IMAP").copy(imap = ServerConfigEmbedded("imap.mail.me.com", 993, "SSL_TLS"))

    /** A sparse file of exactly [bytes] — allocates the size without writing content (fast, no disk churn). */
    private fun stageFileOfSize(messageId: String, index: Int, name: String, bytes: Long) {
        val file = File(cacheDir, "outbox/$messageId/$index").apply { mkdirs() }.resolve(name)
        RandomAccessFile(file, "rw").use { it.setLength(bytes) }
    }

    @Test
    fun `an oversized iCloud message fails cleanly without ever attempting to send (issue #363)`() = runTest {
        stageFileOfSize("m1", index = 0, name = "big.bin", bytes = 21L * 1024 * 1024) // over the 20 MB cap
        val attachmentsJson = listOf(OutgoingAttachment(uri = "content://1", name = "big.bin"))
            .toOutgoingAttachmentsJson()
        coEvery { outboxDao.getAll() } returns listOf(entity(attachments = attachmentsJson))
        coEvery { accountDao.getById("acct") } returns icloudAccount()
        coEvery { connectionFactory.smtpParamsFor(any()) } returns mockk<SmtpParams>()

        assertEquals(Result.retry(), worker().doWork())

        coVerify(exactly = 0) { smtpSender.send(any(), any(), any(), any()) }
        coVerify { outboxDao.setError("m1", match { it.contains("iCloud Mail") && it.contains("MB") }) }
        coVerify(exactly = 0) { outboxDao.delete(any()) }
        val messages = logBuffer.snapshot().map { it.message }
        messages.forEach { assertFalse(it.contains("acct@example.org"), it) }
    }

    @Test
    fun `an iCloud message within the size cap sends normally`() = runTest {
        coEvery { outboxDao.getAll() } returns listOf(entity())
        coEvery { accountDao.getById("acct") } returns icloudAccount()
        coEvery { connectionFactory.smtpParamsFor(any()) } returns mockk<SmtpParams>()

        assertEquals(Result.success(), worker().doWork())

        coVerify { smtpSender.send(any(), "acct@example.org", any(), any()) }
        coVerify { outboxDao.delete("m1") }
    }

    @Test
    fun `sends an Outlook message over Graph, logging a PII-free breadcrumb`() = runTest {
        coEvery { outboxDao.getAll() } returns listOf(entity())
        coEvery { accountDao.getById("acct") } returns account("acct", "OAUTH_OUTLOOK")
        coEvery { connectionFactory.graphTokenFor(any()) } returns "graph-token"

        assertEquals(Result.success(), worker().doWork())

        coVerify { graphSender.send("graph-token", any(), any()) }
        coVerify { outboxDao.delete("m1") }
        coVerify(exactly = 0) { smtpSender.send(any(), any(), any(), any()) }
        val messages = logBuffer.snapshot().map { it.message }
        assertTrue(messages.contains("sent ${accountLogRef("acct")} via Graph"), "messages=$messages")
        messages.forEach { assertFalse(it.contains("acct@example.org"), it) }
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
    fun `a Graph transport error falls back to SMTP and logs a PII-free fallback breadcrumb`() = runTest {
        coEvery { outboxDao.getAll() } returns listOf(entity())
        coEvery { accountDao.getById("acct") } returns account("acct", "OAUTH_OUTLOOK")
        coEvery { connectionFactory.graphTokenFor(any()) } throws RuntimeException("token refresh failed")
        coEvery { connectionFactory.smtpParamsFor(any()) } returns mockk<SmtpParams>()

        assertEquals(Result.success(), worker().doWork())

        coVerify { smtpSender.send(any(), "acct@example.org", any(), any()) }
        coVerify { outboxDao.delete("m1") }
        val messages = logBuffer.snapshot().map { it.message }
        assertTrue(
            messages.any { it.startsWith("Graph send failed for ${accountLogRef("acct")}; falling back to SMTP") },
            "messages=$messages",
        )
        messages.forEach { assertFalse(it.contains("acct@example.org"), it) }
    }

    @Test
    fun `regression #297 - no send breadcrumb ever contains the account email`() = runTest {
        coEvery { outboxDao.getAll() } returns listOf(entity())
        coEvery { accountDao.getById("acct") } returns account("acct", "OAUTH_OUTLOOK")
        coEvery { connectionFactory.graphTokenFor(any()) } throws RuntimeException("token refresh failed")
        coEvery { connectionFactory.smtpParamsFor(any()) } returns mockk<SmtpParams>()

        worker().doWork()

        val snapshot = logBuffer.snapshot()
        // This path logs the outbox-drain, the Graph->SMTP fallback (with a scrubbed throwable), and
        // the send-result breadcrumbs — the richest set of log lines for one message. None may carry
        // the account's raw email, only the non-reversible accountLogRef.
        assertTrue(snapshot.isNotEmpty(), "expected breadcrumbs to be recorded")
        snapshot.forEach { entry ->
            assertFalse(entry.message.contains("acct@example.org"), entry.message)
            assertFalse(entry.message.contains("@example.org"), entry.message)
        }
    }

    @Test
    fun `sends over a real SMTP server end-to-end and logs a PII-free breadcrumb`() = runTest {
        // The "connectivity/send" E2E surface for this ticket: a real SmtpSender talking to a real
        // (in-process) SMTP server, rather than the mocked smtpSender used by the tests above.
        val greenMail = GreenMail(ServerSetupTest.SMTP)
        greenMail.start()
        greenMail.setUser("acct@example.org", "smtp-secret")
        try {
            coEvery { outboxDao.getAll() } returns listOf(entity())
            coEvery { accountDao.getById("acct") } returns account("acct", "PASSWORD_IMAP")
            coEvery { connectionFactory.smtpParamsFor(any()) } returns SmtpParams(
                host = "127.0.0.1",
                port = greenMail.smtp.port,
                security = MailSecurity.NONE,
                username = "acct@example.org",
                secret = "smtp-secret",
                useXoauth2 = false,
            )
            val realSmtpWorker = SendWorker(
                appContext,
                mockk(relaxed = true),
                lazyOutbox,
                lazyAccount,
                SmtpSender(),
                graphSender,
                lazyConnection,
                cacheGuard,
                lazyGrants,
            )

            assertEquals(Result.success(), realSmtpWorker.doWork())
            greenMail.waitForIncomingEmail(1)

            assertEquals(1, greenMail.receivedMessages.size)
            coVerify { outboxDao.delete("m1") }
            val messages = logBuffer.snapshot().map { it.message }
            assertTrue(messages.contains("sent ${accountLogRef("acct")} via SMTP"), "messages=$messages")
            messages.forEach { assertFalse(it.contains("acct@example.org"), it) }
        } finally {
            greenMail.stop()
        }
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
