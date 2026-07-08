// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.repository

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import app.cash.turbine.test
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import jakarta.mail.Flags
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.data.attachment.AttachmentUriGrants
import org.libremail.data.local.dao.AccountDao
import org.libremail.data.local.dao.AttachmentDao
import org.libremail.data.local.dao.DraftDao
import org.libremail.data.local.dao.FolderDao
import org.libremail.data.local.dao.MessageDao
import org.libremail.data.local.dao.OutboxDao
import org.libremail.data.local.entity.AccountEntity
import org.libremail.data.local.entity.AttachmentEntity
import org.libremail.data.local.entity.DraftEntity
import org.libremail.data.local.entity.FolderEntity
import org.libremail.data.local.entity.FolderUnreadCount
import org.libremail.data.local.entity.MessageEntity
import org.libremail.data.local.entity.MessageRouting
import org.libremail.data.local.entity.OutboxEntity
import org.libremail.data.local.entity.ServerConfigEmbedded
import org.libremail.data.settings.AccountSettingsRepository
import org.libremail.data.settings.SignatureRepository
import org.libremail.data.sync.InteractiveImapGate
import org.libremail.data.sync.MailConnectionFactory
import org.libremail.data.sync.SendScheduler
import org.libremail.domain.model.Account
import org.libremail.domain.model.AccountSettings
import org.libremail.domain.model.Draft
import org.libremail.domain.model.ImapConnectionParams
import org.libremail.domain.model.MailSecurity
import org.libremail.domain.model.OutgoingAttachment
import org.libremail.domain.model.OutgoingMessage
import org.libremail.domain.model.ReplyMode
import org.libremail.mail.DownloadedAttachment
import org.libremail.mail.FetchedMessage
import org.libremail.mail.ImapClient
import org.libremail.mail.ReplyContext
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Extends [MailRepositoryImplTest]'s coverage to the methods and edge/error branches it doesn't reach:
 * the observe-* flows, single-message reads, flag/delete/send/search paths, and the "account or row
 * gone mid-flight" fall-throughs. Kept as a separate, purely-additive file so the existing suite (and
 * the in-flight search PR that touches it) is left untouched.
 */
class MailRepositoryImplCoverageTest {

    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val accountDao = mockk<AccountDao>(relaxed = true)
    private val folderDao = mockk<FolderDao>(relaxed = true)
    private val attachmentDao = mockk<AttachmentDao>(relaxed = true)
    private val draftDao = mockk<DraftDao>(relaxed = true)
    private val outboxDao = mockk<OutboxDao>(relaxed = true)
    private val imapClient = mockk<ImapClient>(relaxed = true)
    private val connectionFactory = mockk<MailConnectionFactory>()
    private val context = mockk<Context>(relaxed = true)
    private val sendScheduler = mockk<SendScheduler>(relaxed = true)
    private val accountSettingsRepository = mockk<AccountSettingsRepository>(relaxed = true)
    private val signatureRepository = mockk<SignatureRepository>(relaxed = true)

    private val repository = MailRepositoryImpl(
        context = context,
        messageDao = messageDao,
        accountDao = accountDao,
        attachmentDao = attachmentDao,
        outboxDao = outboxDao,
        draftDao = draftDao,
        folderDao = folderDao,
        imapClient = imapClient,
        connectionFactory = connectionFactory,
        sendScheduler = sendScheduler,
        accountSettingsRepository = accountSettingsRepository,
        signatureRepository = signatureRepository,
        attachmentUriGrants = mockk<AttachmentUriGrants>(relaxed = true),
        interactiveGate = InteractiveImapGate(),
    )

    // openMessage now breadcrumbs via AppLog (issue #358); android.util.Log is a no-op stub under plain
    // JVM tests, so mock it class-wide so no test crashes on the unmocked method.
    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun tearDown() = unmockkAll()

    // --- single-message reads -------------------------------------------------------------------

    @Test
    fun `getMessage returns the mapped domain message when it is cached`() = runTest {
        coEvery { messageDao.getById("acct:INBOX:1") } returns messageEntity("acct:INBOX:1", body = "Hi", isHtml = true)

        val message = repository.getMessage("acct:INBOX:1")

        assertEquals("Hi", message?.body)
        assertTrue(message?.isHtml == true)
    }

    @Test
    fun `getMessage returns null when the message is not cached`() = runTest {
        coEvery { messageDao.getById("missing") } returns null

        assertNull(repository.getMessage("missing"))
    }

    // --- observe-* flows ------------------------------------------------------------------------

    @Test
    fun `observeAttachments maps attachment rows to the domain, carrying inline content ids`() = runTest {
        every { attachmentDao.observeForMessage("m") } returns flowOf(
            listOf(
                AttachmentEntity("m", 0, "a.pdf", "application/pdf", 10L),
                AttachmentEntity("m", 1, "logo.png", "image/png", 4L, contentId = "logo@cid"),
            ),
        )

        repository.observeAttachments("m").test {
            val list = awaitItem()
            assertEquals(2, list.size)
            assertNull(list[0].contentId)
            assertEquals("logo@cid", list[1].contentId)
            awaitComplete()
        }
    }

    @Test
    fun `observeUnreadCounts maps the aggregate rows to domain unread counts`() = runTest {
        every { messageDao.observeUnreadCounts() } returns flowOf(
            listOf(FolderUnreadCount("acct", "INBOX", 3), FolderUnreadCount("acct", "Work", 1)),
        )

        repository.observeUnreadCounts().test {
            val counts = awaitItem()
            assertEquals(listOf(3, 1), counts.map { it.count })
            assertEquals("INBOX", counts.first().folder)
            awaitComplete()
        }
    }

    @Test
    fun `observeDrafts maps draft rows to the domain, decoding their attachments`() = runTest {
        every { draftDao.observeAll() } returns flowOf(
            listOf(draftEntity("d1", """[{"uri":"content://f","name":"a.txt"}]""")),
        )

        repository.observeDrafts().test {
            val drafts = awaitItem()
            assertEquals("content://f", drafts.single().attachments.single().uri)
            awaitComplete()
        }
    }

    @Test
    fun `observeOutbox maps outbox rows to the domain`() = runTest {
        every { outboxDao.observeAll() } returns flowOf(listOf(outboxEntity("o1")))

        repository.observeOutbox().test {
            val list = awaitItem()
            assertEquals("o1", list.single().id)
            assertEquals("bob@example.org", list.single().to)
            awaitComplete()
        }
    }

    // --- drafts ---------------------------------------------------------------------------------

    @Test
    fun `getDraft returns the mapped draft when present`() = runTest {
        coEvery { draftDao.getById("d1") } returns draftEntity("d1", "")

        assertEquals("d1", repository.getDraft("d1")?.id)
    }

    @Test
    fun `getDraft returns null when the draft is absent`() = runTest {
        coEvery { draftDao.getById("nope") } returns null

        assertNull(repository.getDraft("nope"))
    }

    @Test
    fun `saveDraft upserts the mapped draft entity`() = runTest {
        val saved = slot<DraftEntity>()
        coEvery { draftDao.upsert(capture(saved)) } just Runs

        repository.saveDraft(
            Draft(
                id = "d9",
                accountId = "acct",
                to = "bob@example.org",
                cc = "",
                subject = "Hi",
                body = "Yo",
                updatedAt = 7L,
            ),
        )

        assertEquals("d9", saved.captured.id)
        assertEquals("bob@example.org", saved.captured.toAddresses)
    }

    // --- setStarred / deleteMessage (server-reachable vs. local-only) ---------------------------

    @Test
    fun `setStarred writes the local flag then pushes the FLAGGED change to the server`() = runTest {
        val id = "acct:INBOX:5"
        coEvery { messageDao.getRouting(id) } returns messageRouting(id, "INBOX")
        coEvery { accountDao.getById("acct") } returns accountEntity()
        coEvery { connectionFactory.imapParamsFor(any()) } returns imapParams()

        val result = repository.setStarred(id, true)

        assertTrue(result.isSuccess)
        coVerify { messageDao.setStarred(id, true) }
        coVerify { imapClient.setFlag(any(), "INBOX", "5", Flags.Flag.FLAGGED, true) }
    }

    @Test
    fun `setStarred still succeeds locally when the message has no cached routing`() = runTest {
        val id = "acct:INBOX:6"
        coEvery { messageDao.getRouting(id) } returns null

        val result = repository.setStarred(id, false)

        assertTrue(result.isSuccess)
        coVerify { messageDao.setStarred(id, false) }
        coVerify(exactly = 0) { imapClient.setFlag(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `deleteMessage removes the local row and deletes it on the server`() = runTest {
        val id = "acct:INBOX:7"
        coEvery { messageDao.getRouting(id) } returns messageRouting(id, "INBOX")
        coEvery { accountDao.getById("acct") } returns accountEntity()
        coEvery { connectionFactory.imapParamsFor(any()) } returns imapParams()

        val result = repository.deleteMessage(id)

        assertTrue(result.isSuccess)
        coVerify { messageDao.deleteById(id) }
        coVerify { imapClient.deleteMessage(any(), "INBOX", "7") }
    }

    @Test
    fun `deleteMessage removes the local row even when there is nothing to delete server-side`() = runTest {
        val id = "acct:INBOX:8"
        coEvery { messageDao.getRouting(id) } returns null

        val result = repository.deleteMessage(id)

        assertTrue(result.isSuccess)
        coVerify { messageDao.deleteById(id) }
        coVerify(exactly = 0) { imapClient.deleteMessage(any(), any(), any()) }
    }

    // --- sendMessage / copyAttachments ----------------------------------------------------------

    @Test
    fun `sendMessage queues the outbox row and triggers the send worker`() = runTest {
        every { context.cacheDir } returns Files.createTempDirectory("outbox").toFile()
        coEvery { accountDao.getById("acct") } returns accountEntity()
        val saved = slot<OutboxEntity>()
        coEvery { outboxDao.insert(capture(saved)) } just Runs

        val result = repository.sendMessage(
            OutgoingMessage(accountId = "acct", to = "bob@example.org", subject = "Hi", body = "Yo"),
        )

        assertTrue(result.isSuccess)
        assertEquals("acct", saved.captured.accountId)
        assertEquals("bob@example.org", saved.captured.toAddresses)
        assertEquals("", saved.captured.attachments) // no attachments -> empty json
        verify { sendScheduler.sendNow() }
    }

    @Test
    fun `sendMessage fails when the sending account is missing`() = runTest {
        coEvery { accountDao.getById("gone") } returns null

        val result = repository.sendMessage(
            OutgoingMessage(accountId = "gone", to = "b@x.org", subject = "s", body = "b"),
        )

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { outboxDao.insert(any()) }
        verify(exactly = 0) { sendScheduler.sendNow() }
    }

    @Test
    fun `sendMessage copies each attachment into the outbox staging dir under a sanitized name`() = runTest {
        val cache = Files.createTempDirectory("outbox").toFile()
        every { context.cacheDir } returns cache
        coEvery { accountDao.getById("acct") } returns accountEntity()
        val saved = slot<OutboxEntity>()
        coEvery { outboxDao.insert(capture(saved)) } just Runs
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } returns mockk()
        val resolver = mockk<ContentResolver>()
        every { context.contentResolver } returns resolver
        every { resolver.openInputStream(any()) } returns ByteArrayInputStream("PDFDATA".toByteArray())

        val result = repository.sendMessage(
            OutgoingMessage(
                accountId = "acct",
                to = "bob@example.org",
                subject = "Hi",
                body = "Yo",
                attachments = listOf(OutgoingAttachment("content://pick/report", "../evil/report.pdf")),
            ),
        )

        assertTrue(result.isSuccess)
        // The staged file keeps only the sanitized leaf name (no path traversal) with the copied bytes.
        val staged = File(cache, "outbox/${saved.captured.id}/0/report.pdf")
        assertTrue(staged.exists())
        assertEquals("PDFDATA", staged.readText())
        // The persisted json still carries the picked URI for the send worker.
        assertTrue(saved.captured.attachments.contains("content://pick/report"))
    }

    @Test
    fun `sendMessage tolerates an attachment whose bytes cannot be read and still queues the message`() = runTest {
        val cache = Files.createTempDirectory("outbox").toFile()
        every { context.cacheDir } returns cache
        coEvery { accountDao.getById("acct") } returns accountEntity()
        val saved = slot<OutboxEntity>()
        coEvery { outboxDao.insert(capture(saved)) } just Runs
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } returns mockk()
        val resolver = mockk<ContentResolver>()
        every { context.contentResolver } returns resolver
        // An unreadable URI resolves to no stream; the copy is skipped but the send still proceeds.
        every { resolver.openInputStream(any()) } returns null

        val result = repository.sendMessage(
            OutgoingMessage(
                accountId = "acct",
                to = "bob@example.org",
                subject = "Hi",
                body = "Yo",
                attachments = listOf(OutgoingAttachment("content://pick/broken", "x.bin")),
            ),
        )

        assertTrue(result.isSuccess)
        assertFalse(File(cache, "outbox/${saved.captured.id}/0/x.bin").exists())
        verify { sendScheduler.sendNow() }
    }

    @Test
    fun `buildReplyDraft omits the signature block when the account has signatures disabled`() = runTest {
        val id = "acct:INBOX:20"
        coEvery { messageDao.getRouting(id) } returns messageRouting(id, "INBOX")
        coEvery { accountDao.getById("acct") } returns accountEntity()
        coEvery { connectionFactory.imapParamsFor(any()) } returns imapParams()
        coEvery { accountSettingsRepository.get("acct") } returns AccountSettings("acct", signatureEnabled = false)
        coEvery { imapClient.fetchForReply(any(), "INBOX", "20") } returns ReplyContext(
            fromEmail = "boss@example.org",
            toRecipients = listOf("ada@example.org"),
            ccRecipients = emptyList(),
            subject = "Plan",
            sentDateMillis = 0L,
            body = "Original",
            isHtml = false,
        )
        val draft = slot<DraftEntity>()
        coEvery { draftDao.upsert(capture(draft)) } just Runs

        val result = repository.buildReplyDraft(id, ReplyMode.REPLY)

        assertTrue(result.isSuccess)
        // With signatures off, no RFC 3676 "-- " delimiter is baked above the quote, and the default
        // signature is never even resolved.
        assertFalse(draft.captured.body.contains("-- "))
        coVerify(exactly = 0) { signatureRepository.getDefault(any()) }
    }

    @Test
    fun `retryOutbox re-triggers the send worker`() = runTest {
        repository.retryOutbox()

        verify { sendScheduler.sendNow() }
    }

    // --- server search --------------------------------------------------------------------------

    @Test
    fun `searchServer queries every account when unfiltered and stores hits as search-only`() = runTest {
        coEvery { accountDao.getAll() } returns listOf(
            accountEntity(id = "acct"),
            accountEntity(id = "acct2", email = "bob@example.org"),
        )
        coEvery { connectionFactory.imapParamsFor(any()) } returns imapParams()
        coEvery { imapClient.search(any(), "INBOX", "invoice", any()) } returns listOf(
            FetchedMessage("11", "Ada", "ada@example.org", "Invoice", 1L, isRead = false, isFlagged = false),
        )
        val inserted = slot<List<MessageEntity>>()
        coEvery { messageDao.insertNew(capture(inserted)) } just Runs

        repository.searchServer("invoice", accountId = null, folder = "INBOX")

        // Both accounts are searched, and every hit is marked search-only (inInbox = false) so it
        // never masquerades as a synced row.
        coVerify(exactly = 2) { imapClient.search(any(), "INBOX", "invoice", any()) }
        assertTrue(inserted.captured.isNotEmpty() && inserted.captured.all { !it.inInbox })
        coVerify { messageDao.updateHeaderContent(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `searchServer restricts the search to the requested account`() = runTest {
        coEvery { accountDao.getAll() } returns listOf(
            accountEntity(id = "acct"),
            accountEntity(id = "acct2", email = "bob@example.org"),
        )
        val searched = slot<Account>()
        coEvery { connectionFactory.imapParamsFor(capture(searched)) } returns imapParams()
        coEvery { imapClient.search(any(), "INBOX", "q", any()) } returns emptyList()

        repository.searchServer("q", accountId = "acct2", folder = "INBOX")

        // Only the filtered account is contacted; an empty result inserts an empty batch and updates
        // no headers.
        assertEquals("acct2", searched.captured.id)
        coVerify(exactly = 1) { imapClient.search(any(), "INBOX", "q", any()) }
        coVerify(exactly = 1) { messageDao.insertNew(emptyList()) }
        coVerify(exactly = 0) { messageDao.updateHeaderContent(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `clearSearchResults deletes the transient search rows`() = runTest {
        repository.clearSearchResults()

        coVerify { messageDao.deleteSearchRows() }
    }

    // --- inlineImages edge cases ----------------------------------------------------------------

    @Test
    fun `inlineImages returns nothing when the message has no routing`() = runTest {
        coEvery { messageDao.getRouting("gone") } returns null

        assertTrue(repository.inlineImages("gone").isEmpty())
    }

    @Test
    fun `inlineImages omits a cid part whose bytes cannot be fetched`() = runTest {
        every { context.cacheDir } returns Files.createTempDirectory("attach").toFile()
        val id = "acct:INBOX:9"
        coEvery { messageDao.getRouting(id) } returns messageRouting(id, "INBOX")
        coEvery { attachmentDao.getForMessage(id) } returns listOf(
            AttachmentEntity(id, 0, "logo.png", "image/png", 4L, contentId = "logo@cid"),
        )
        // No cached file and the account is gone, so the on-demand fetch throws and the image is dropped.
        coEvery { accountDao.getById("acct") } returns null

        assertTrue(repository.inlineImages(id).isEmpty())
    }

    // --- openMessage fall-throughs --------------------------------------------------------------

    @Test
    fun `openMessage fails when the message id has no routing`() = runTest {
        coEvery { messageDao.getRouting("gone") } returns null

        assertTrue(repository.openMessage("gone").isFailure)
    }

    @Test
    fun `openMessage skips the server fetch when the account has been removed`() = runTest {
        val id = "acct:INBOX:10"
        coEvery { messageDao.getRouting(id) } returns messageRouting(id, "INBOX") // unread, no body
        coEvery { accountDao.getById("acct") } returns null
        coEvery { messageDao.getById(id) } returns messageEntity(id, body = "stale")

        val result = repository.openMessage(id)

        assertEquals("stale", result.getOrThrow().body)
        coVerify(exactly = 0) { imapClient.fetchBodyMarkingSeen(any(), any(), any()) }
        coVerify(exactly = 0) { messageDao.setRead(any(), any()) }
    }

    @Test
    fun `openMessage fails when the row vanishes before the body read`() = runTest {
        val id = "acct:INBOX:11"
        coEvery { messageDao.getRouting(id) } returns messageRouting(id, "INBOX", bodyFetched = true, isRead = true)
        coEvery { messageDao.getById(id) } returns null

        assertTrue(repository.openMessage(id).isFailure)
    }

    @Test
    fun `refreshFolders fails when the account is unknown`() = runTest {
        coEvery { accountDao.getById("gone") } returns null

        assertTrue(repository.refreshFolders("gone").isFailure)
    }

    // --- prefetchMessage / downloadAttachment ---------------------------------------------------

    @Test
    fun `prefetchMessage is a no-op success when the message has no routing`() = runTest {
        coEvery { messageDao.getRouting("gone") } returns null

        assertTrue(repository.prefetchMessage("gone").isSuccess)
        coVerify(exactly = 0) { imapClient.fetchBodyPeek(any(), any(), any()) }
    }

    @Test
    fun `prefetchMessage is a no-op success when the account has been removed`() = runTest {
        val id = "acct:INBOX:12"
        coEvery { messageDao.getRouting(id) } returns messageRouting(id, "INBOX")
        coEvery { accountDao.getById("acct") } returns null

        assertTrue(repository.prefetchMessage(id).isSuccess)
        coVerify(exactly = 0) { imapClient.fetchBodyPeek(any(), any(), any()) }
    }

    @Test
    fun `downloadAttachment falls back to a default filename when the part metadata is missing`() = runTest {
        val cache = Files.createTempDirectory("attach").toFile()
        every { context.cacheDir } returns cache
        val id = "acct:INBOX:13"
        coEvery { messageDao.getRouting(id) } returns messageRouting(id, "INBOX")
        coEvery { attachmentDao.getForMessage(id) } returns emptyList() // no metadata for part 0
        coEvery { accountDao.getById("acct") } returns accountEntity()
        coEvery { connectionFactory.imapParamsFor(any()) } returns imapParams()
        coEvery { imapClient.fetchAttachment(any(), "INBOX", "13", 0) } returns
            DownloadedAttachment("attachment", "application/octet-stream", "bytes".toByteArray())

        val result = repository.downloadAttachment(id, 0)

        assertTrue(result.isSuccess)
        // Stored under the "attachment" fallback name since no cached metadata carried a filename.
        assertEquals(File(cache, "attachments/acct_INBOX_13/0/attachment"), result.getOrThrow())
    }

    // --- batch move fall-throughs ---------------------------------------------------------------

    @Test
    fun `moveToFolder skips the server move for messages already in the destination folder`() = runTest {
        val id = "acct:Receipts:14"
        coEvery { messageDao.getRoutingByIds(listOf(id)) } returns listOf(messageRouting(id, "Receipts"))
        coEvery { accountDao.getById("acct") } returns accountEntity()
        coEvery { connectionFactory.imapParamsFor(any()) } returns imapParams()

        val result = repository.moveToFolder(listOf(id), "Receipts")

        assertTrue(result.isSuccess)
        coVerify { messageDao.deleteByIds(listOf(id)) } // still optimistically removed locally
        coVerify(exactly = 0) { imapClient.moveMessages(any(), any(), any(), any()) } // no self-move
    }

    @Test
    fun `expunge skips messages whose account no longer exists`() = runTest {
        val id = "acct:INBOX:15"
        coEvery { messageDao.getRoutingByIds(listOf(id)) } returns listOf(messageRouting(id, "INBOX"))
        coEvery { accountDao.getById("acct") } returns null // account removed mid-flight

        val result = repository.expunge(listOf(id))

        assertTrue(result.isSuccess)
        coVerify { messageDao.deleteByIds(listOf(id)) } // local removal still happens
        coVerify(exactly = 0) { imapClient.deleteMessages(any(), any(), any()) } // nothing pushed server-side
    }

    // --- role-folder resolution: a same-role folder that is not selectable ----------------------

    @Test
    fun `archive ignores a same-role folder that is not selectable and fails with no target`() = runTest {
        val id = "acct:INBOX:30"
        coEvery { messageDao.getRoutingByIds(listOf(id)) } returns listOf(messageRouting(id, "INBOX"))
        coEvery { accountDao.getById("acct") } returns accountEntity()
        coEvery { connectionFactory.imapParamsFor(any()) } returns imapParams()
        // The only ARCHIVE-role folder is a non-selectable container (e.g. Gmail's "[Gmail]" parent),
        // so it can't be a move target; with no selectable archive folder, the archive fails.
        coEvery { folderDao.getForAccountOnce("acct") } returns listOf(
            FolderEntity(
                accountId = "acct",
                fullName = "[Gmail]",
                displayName = "[Gmail]",
                role = "ARCHIVE",
                selectable = false,
                sortOrder = 0,
            ),
        )
        coEvery { imapClient.listFolders(any()) } returns emptyList()

        assertTrue(repository.archive(listOf(id)).isFailure)
        coVerify(exactly = 0) { imapClient.moveMessages(any(), any(), any(), any()) }
    }

    // --- cancelOutboxMessage ---------------------------------------------------------------------

    @Test
    fun `cancelOutboxMessage deletes the row and its staging dir, releasing attachment grants`() = runTest {
        val cache = Files.createTempDirectory("outbox").toFile()
        every { context.cacheDir } returns cache
        File(cache, "outbox/o1").mkdirs()
        coEvery { outboxDao.getById("o1") } returns outboxEntity("o1").copy(
            attachments = """[{"uri":"content://pick/a","name":"a.txt"}]""",
        )

        repository.cancelOutboxMessage("o1")

        coVerify { outboxDao.delete("o1") }
        assertFalse(File(cache, "outbox/o1").exists())
    }

    @Test
    fun `cancelOutboxMessage tolerates an already-removed row`() = runTest {
        every { context.cacheDir } returns Files.createTempDirectory("outbox").toFile()
        coEvery { outboxDao.getById("gone") } returns null

        repository.cancelOutboxMessage("gone")

        coVerify { outboxDao.delete("gone") }
    }

    // --- fixtures -------------------------------------------------------------------------------

    private fun messageEntity(
        id: String,
        folder: String = "INBOX",
        accountId: String = "acct",
        body: String = "",
        isHtml: Boolean = false,
        bodyFetched: Boolean = false,
        isRead: Boolean = false,
    ) = MessageEntity(
        id = id,
        accountId = accountId,
        sender = "Ada",
        senderEmail = "ada@example.org",
        subject = "Hi",
        snippet = "snippet",
        body = body,
        isHtml = isHtml,
        timestampMillis = 1_000L,
        isRead = isRead,
        isStarred = false,
        folder = folder,
        bodyFetched = bodyFetched,
    )

    private fun messageRouting(
        id: String,
        folder: String,
        accountId: String = "acct",
        bodyFetched: Boolean = false,
        isRead: Boolean = false,
    ) = MessageRouting(
        id = id,
        accountId = accountId,
        folder = folder,
        uid = id.substringAfterLast(':').toLongOrNull() ?: 0L,
        isRead = isRead,
        isStarred = false,
        bodyFetched = bodyFetched,
        isHtml = false,
    )

    private fun draftEntity(id: String, attachmentsJson: String) = DraftEntity(
        id = id,
        accountId = "acct",
        toAddresses = "bob@example.org",
        ccAddresses = "",
        bccAddresses = "",
        subject = "Hi",
        body = "body",
        updatedAt = 0L,
        attachments = attachmentsJson,
    )

    private fun outboxEntity(id: String) = OutboxEntity(
        id = id,
        accountId = "acct",
        toAddresses = "bob@example.org",
        ccAddresses = "",
        subject = "Hi",
        body = "body",
        createdAt = 0L,
    )

    private fun accountEntity(id: String = "acct", email: String = "ada@example.org") = AccountEntity(
        id = id,
        email = email,
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
