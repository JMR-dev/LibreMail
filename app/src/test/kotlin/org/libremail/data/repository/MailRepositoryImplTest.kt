// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.repository

import android.content.Context
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.testing.asSnapshot
import app.cash.turbine.test
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import jakarta.mail.Flags
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.libremail.data.local.dao.AccountDao
import org.libremail.data.local.dao.AttachmentDao
import org.libremail.data.local.dao.DraftDao
import org.libremail.data.local.dao.FolderDao
import org.libremail.data.local.dao.MessageDao
import org.libremail.data.local.entity.AccountEntity
import org.libremail.data.local.entity.AttachmentEntity
import org.libremail.data.local.entity.DraftEntity
import org.libremail.data.local.entity.FolderEntity
import org.libremail.data.local.entity.MessageEntity
import org.libremail.data.local.entity.MessageSummary
import org.libremail.data.local.entity.ServerConfigEmbedded
import org.libremail.data.settings.AccountSettingsRepository
import org.libremail.data.settings.SignatureRepository
import org.libremail.data.sync.MailConnectionFactory
import org.libremail.domain.model.AccountSettings
import org.libremail.domain.model.FolderRole
import org.libremail.domain.model.ImapConnectionParams
import org.libremail.domain.model.MailSecurity
import org.libremail.domain.model.ReplyMode
import org.libremail.mail.AttachmentPart
import org.libremail.mail.DownloadedAttachment
import org.libremail.mail.FetchedFolder
import org.libremail.mail.ImapClient
import org.libremail.mail.MessageContent
import org.libremail.mail.ReplyContext
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MailRepositoryImplTest {

    private val messageDao = mockk<MessageDao>()
    private val accountDao = mockk<AccountDao>()
    private val folderDao = mockk<FolderDao>()
    private val attachmentDao = mockk<AttachmentDao>(relaxed = true)
    private val draftDao = mockk<DraftDao>()

    // Relaxed: move/delete return jakarta Message[] (from Folder.expunge), which we only verify, not stub.
    private val imapClient = mockk<ImapClient>(relaxed = true)
    private val connectionFactory = mockk<MailConnectionFactory>()
    private val context = mockk<Context>(relaxed = true)
    private val accountSettingsRepository = mockk<AccountSettingsRepository>()
    private val signatureRepository = mockk<SignatureRepository>()
    private val repository = MailRepositoryImpl(
        context = context,
        messageDao = messageDao,
        accountDao = accountDao,
        attachmentDao = attachmentDao,
        outboxDao = mockk(),
        draftDao = draftDao,
        folderDao = folderDao,
        imapClient = imapClient,
        connectionFactory = connectionFactory,
        sendScheduler = mockk(),
        accountSettingsRepository = accountSettingsRepository,
        signatureRepository = signatureRepository,
    )

    @Test
    fun `observeFolderMessages is empty when the folder has no cached rows`() = runTest {
        every { messageDao.observeFolderSummaries("acct", "INBOX") } returns flowOf(emptyList())
        repository.observeFolderMessages("acct", "INBOX").test {
            assertTrue(awaitItem().isEmpty())
            awaitComplete()
        }
    }

    @Test
    fun `observeFolderMessages maps the folder's cached entities`() = runTest {
        every { messageDao.observeFolderSummaries("acct", "INBOX") } returns
            flowOf(listOf(messageSummary("1", "INBOX")))
        repository.observeFolderMessages("acct", "INBOX").test {
            val items = awaitItem()
            assertEquals(1, items.size)
            assertEquals("Ada", items.first().sender)
            assertEquals("INBOX", items.first().folder)
            awaitComplete()
        }
    }

    @Test
    fun `observeUnifiedFolderMessages maps the folder's rows across accounts`() = runTest {
        every { messageDao.observeUnifiedFolderSummaries("INBOX") } returns
            flowOf(listOf(messageSummary("1", "INBOX")))
        repository.observeUnifiedFolderMessages("INBOX").test {
            assertEquals(1, awaitItem().size)
            awaitComplete()
        }
    }

    @Test
    fun `pagedUnifiedFolderMessages maps the paged summaries to domain messages`() = runTest {
        every { messageDao.pagingUnifiedFolderSummaries("INBOX") } returns FakeSummaryPagingSource(
            listOf(messageSummary("1", "INBOX"), messageSummary("2", "INBOX", accountId = "acct2")),
        )

        val items = repository.pagedUnifiedFolderMessages("INBOX").asSnapshot()

        assertEquals(listOf("1", "2"), items.map { it.id })
        assertEquals("Ada", items.first().sender)
        // The list projection never carries a body — the reader loads it on demand (see MessageSummary).
        assertEquals("", items.first().body)
    }

    @Test
    fun `observeFolders maps cached folders with their roles and server special-use flag`() = runTest {
        every { folderDao.observeForAccount("acct") } returns flowOf(
            listOf(
                FolderEntity(
                    accountId = "acct",
                    fullName = "[Gmail]/Sent Mail",
                    displayName = "Sent Mail",
                    role = "SENT",
                    selectable = true,
                    sortOrder = 1,
                    specialUse = true,
                ),
            ),
        )
        repository.observeFolders("acct").test {
            val folders = awaitItem()
            assertEquals(1, folders.size)
            assertEquals(FolderRole.SENT, folders.first().role)
            assertEquals("Sent Mail", folders.first().displayName)
            // toDomain must carry the persisted special-use flag through to the domain model.
            assertTrue(folders.first().specialUse)
            awaitComplete()
        }
    }

    @Test
    fun `refreshFolders persists the server special-use flag derived from folder attributes`() = runTest {
        coEvery { accountDao.getById("acct") } returns accountEntity()
        coEvery { connectionFactory.imapParamsFor(any()) } returns imapParams()
        // A provider-built \Drafts folder (RFC 6154 SPECIAL-USE) beside an attribute-less user folder.
        coEvery { imapClient.listFolders(any()) } returns listOf(
            FetchedFolder("[Gmail]/Drafts", "Drafts", listOf("\\Drafts"), selectable = true),
            FetchedFolder("Receipts", "Receipts", emptyList(), selectable = true),
        )
        val persisted = slot<List<FolderEntity>>()
        coEvery { folderDao.replaceForAccount(any(), capture(persisted)) } just Runs

        val result = repository.refreshFolders("acct")

        assertTrue(result.isSuccess)
        // The one production link (FetchedFolder.toEntity) must persist server special-use per folder,
        // alongside the role it derives from the same attributes.
        assertEquals(listOf(true, false), persisted.captured.map { it.specialUse })
        assertEquals(listOf("DRAFTS", "NORMAL"), persisted.captured.map { it.role })
    }

    @Test
    fun `openMessage fetches the body from the message's own folder, not the inbox`() = runTest {
        val id = "acct:Archive:5"
        coEvery { messageDao.getById(id) } returns messageEntity(id, "Archive")
        coEvery { accountDao.getById("acct") } returns accountEntity()
        coEvery { connectionFactory.imapParamsFor(any()) } returns imapParams()
        coEvery { imapClient.fetchBodyMarkingSeen(any(), "Archive", "5") } returns
            MessageContent("Body text", isHtml = false)
        coEvery { messageDao.updateBody(id, any(), any(), any()) } just Runs
        coEvery { messageDao.setRead(id, true) } just Runs

        repository.openMessage(id)

        // The UID (5) must be resolved against the message's folder (Archive), since IMAP UIDs are
        // unique only within a folder.
        coVerify { imapClient.fetchBodyMarkingSeen(any(), "Archive", "5") }
    }

    @Test
    fun `openMessage derives a readable plain-text snippet from an HTML body`() = runTest {
        val id = "acct:INBOX:20"
        coEvery { messageDao.getById(id) } returns messageEntity(id, "INBOX")
        coEvery { accountDao.getById("acct") } returns accountEntity()
        coEvery { connectionFactory.imapParamsFor(any()) } returns imapParams()
        coEvery { imapClient.fetchBodyMarkingSeen(any(), "INBOX", "20") } returns MessageContent(
            "<style>.x{color:red}</style><p>Tom &amp; Jerry say &quot;hi&quot;</p>",
            isHtml = true,
        )
        val snippet = slot<String>()
        coEvery { messageDao.updateBody(id, any(), any(), capture(snippet)) } just Runs
        coEvery { messageDao.setRead(id, true) } just Runs

        repository.openMessage(id)

        // Style content must not leak and entities must be decoded (the derivation honors isHtml).
        assertEquals("Tom & Jerry say \"hi\"", snippet.captured)
    }

    /**
     * The #148 fix: when the body is already cached and the message is unread, openMessage() must not
     * await the SEEN-flag IMAP round trip before returning. Uses `runBlocking` (not `runTest`) and a
     * [CompletableDeferred] gate — same idiom as `MailMaintenanceGateTest` — because the behavior under
     * test is genuine concurrency between the caller's coroutine and the repository's own background
     * scope, not something a virtual-time test dispatcher can observe.
     */
    @Test
    fun `openMessage returns without awaiting the background SEEN-flag push`() {
        // Block body (not `= runBlocking { ... }`): the last statement below returns Boolean
        // (CompletableDeferred.complete), and JUnit4 requires @Test methods to return void/Unit.
        runBlocking {
            val id = "acct:INBOX:40"
            coEvery { messageDao.getById(id) } returns
                messageEntity(id, "INBOX", bodyFetched = true) // isRead = false
            coEvery { accountDao.getById("acct") } returns accountEntity()
            coEvery { connectionFactory.imapParamsFor(any()) } returns imapParams()
            coEvery { messageDao.setRead(id, true) } just Runs
            val flagPushStarted = CompletableDeferred<Unit>()
            val releaseFlagPush = CompletableDeferred<Unit>()
            coEvery { imapClient.setFlag(any(), "INBOX", "40", Flags.Flag.SEEN, true) } coAnswers {
                flagPushStarted.complete(Unit)
                releaseFlagPush.await() // stays "in flight" until this test explicitly releases it
            }

            val result = repository.openMessage(id)

            // openMessage already completed successfully even though the mocked setFlag call above is
            // still parked on releaseFlagPush — proof it is not on the awaited path.
            assertTrue(result.isSuccess)
            coVerify { messageDao.setRead(id, true) } // the optimistic local write happens synchronously
            // The push must still actually happen, just off this path — bounded wait, not indefinite.
            withTimeout(FLAG_PUSH_AWAIT_TIMEOUT_MS) { flagPushStarted.await() }
            releaseFlagPush.complete(Unit) // let the background coroutine finish cleanly before the test ends
        }
    }

    /** Best-effort means retried, not "one attempt and silently give up" (#148 design point 2). */
    @Test
    fun `a failed SEEN-flag push is retried in the background`() = runBlocking {
        val id = "acct:INBOX:41"
        coEvery { messageDao.getById(id) } returns messageEntity(id, "INBOX", bodyFetched = true)
        coEvery { accountDao.getById("acct") } returns accountEntity()
        coEvery { connectionFactory.imapParamsFor(any()) } returns imapParams()
        coEvery { messageDao.setRead(id, true) } just Runs
        coEvery { imapClient.setFlag(any(), "INBOX", "41", Flags.Flag.SEEN, true) } throws RuntimeException("boom")

        repository.openMessage(id)

        // The first attempt fails immediately; observing a second proves this is a retry loop rather than
        // a single best-effort attempt that gives up silently after one failure.
        coVerify(timeout = FLAG_PUSH_RETRY_VERIFY_TIMEOUT_MS, atLeast = 2) {
            imapClient.setFlag(any(), "INBOX", "41", Flags.Flag.SEEN, true)
        }
    }

    @Test
    fun `prefetchMessage leaves a plain-text body's literal angle brackets in the snippet`() = runTest {
        val cache = Files.createTempDirectory("attach").toFile()
        every { context.cacheDir } returns cache
        val id = "acct:INBOX:21"
        coEvery { messageDao.getById(id) } returns messageEntity(id, "INBOX")
        coEvery { accountDao.getById("acct") } returns accountEntity()
        coEvery { connectionFactory.imapParamsFor(any()) } returns imapParams()
        coEvery { imapClient.fetchBodyPeek(any(), "INBOX", "21") } returns
            MessageContent("Reply to <ada@example.org>:  3 < 5", isHtml = false)
        val snippet = slot<String>()
        coEvery { messageDao.updateBody(id, any(), any(), capture(snippet)) } just Runs
        coEvery { attachmentDao.getForMessage(id) } returns emptyList()

        repository.prefetchMessage(id)

        // No tag stripping for plain text — only whitespace collapsing (and the length cap) applies.
        assertEquals("Reply to <ada@example.org>: 3 < 5", snippet.captured)
    }

    @Test
    fun `archive moves messages to the account's archive folder and drops the local rows`() = runTest {
        val id = "acct:INBOX:5"
        coEvery { messageDao.getById(id) } returns messageEntity(id, "INBOX")
        coEvery { messageDao.deleteByIds(listOf(id)) } just Runs
        coEvery { accountDao.getById("acct") } returns accountEntity()
        coEvery { connectionFactory.imapParamsFor(any()) } returns imapParams()
        coEvery { folderDao.getForAccountOnce("acct") } returns listOf(folderEntity("Archive", "ARCHIVE"))

        val result = repository.archive(listOf(id))

        assertTrue(result.isSuccess)
        coVerify { messageDao.deleteByIds(listOf(id)) } // optimistic local removal
        coVerify { imapClient.moveMessages(any(), "INBOX", listOf("5"), "Archive") }
    }

    @Test
    fun `trash falls back to a permanent delete when the account has no trash folder`() = runTest {
        val id = "acct:INBOX:7"
        coEvery { messageDao.getById(id) } returns messageEntity(id, "INBOX")
        coEvery { messageDao.deleteByIds(any()) } just Runs
        coEvery { accountDao.getById("acct") } returns accountEntity()
        coEvery { connectionFactory.imapParamsFor(any()) } returns imapParams()
        // No Trash folder, and a forced refresh doesn't add one.
        coEvery { folderDao.getForAccountOnce("acct") } returns listOf(folderEntity("INBOX", "INBOX"))
        coEvery { folderDao.replaceForAccount(any(), any()) } just Runs
        coEvery { imapClient.listFolders(any()) } returns emptyList()

        val result = repository.trash(listOf(id))

        assertTrue(result.isSuccess)
        coVerify { imapClient.deleteMessage(any(), "INBOX", "7") }
    }

    @Test
    fun `archive fails when the account has no archive folder`() = runTest {
        val id = "acct:INBOX:9"
        coEvery { messageDao.getById(id) } returns messageEntity(id, "INBOX")
        coEvery { messageDao.deleteByIds(any()) } just Runs
        coEvery { accountDao.getById("acct") } returns accountEntity()
        coEvery { connectionFactory.imapParamsFor(any()) } returns imapParams()
        coEvery { folderDao.getForAccountOnce("acct") } returns listOf(folderEntity("INBOX", "INBOX"))
        coEvery { folderDao.replaceForAccount(any(), any()) } just Runs
        coEvery { imapClient.listFolders(any()) } returns emptyList()

        assertTrue(repository.archive(listOf(id)).isFailure)
    }

    @Test
    fun `expunge permanently deletes each message from its own folder`() = runTest {
        val id = "acct:Trash:3"
        coEvery { messageDao.getById(id) } returns messageEntity(id, "Trash")
        coEvery { messageDao.deleteByIds(listOf(id)) } just Runs
        coEvery { accountDao.getById("acct") } returns accountEntity()
        coEvery { connectionFactory.imapParamsFor(any()) } returns imapParams()

        repository.expunge(listOf(id))

        coVerify { imapClient.deleteMessage(any(), "Trash", "3") }
    }

    @Test
    fun `moveToFolder moves messages to the chosen destination`() = runTest {
        val id = "acct:INBOX:11"
        coEvery { messageDao.getById(id) } returns messageEntity(id, "INBOX")
        coEvery { messageDao.deleteByIds(listOf(id)) } just Runs
        coEvery { accountDao.getById("acct") } returns accountEntity()
        coEvery { connectionFactory.imapParamsFor(any()) } returns imapParams()

        repository.moveToFolder(listOf(id), "Receipts")

        coVerify { imapClient.moveMessages(any(), "INBOX", listOf("11"), "Receipts") }
    }

    @Test
    fun `buildReplyDraft fetches the original and saves a prefilled draft`() = runTest {
        val id = "acct:INBOX:2"
        coEvery { messageDao.getById(id) } returns messageEntity(id, "INBOX")
        coEvery { accountDao.getById("acct") } returns accountEntity()
        coEvery { accountSettingsRepository.get(any()) } returns AccountSettings("acct")
        coEvery { signatureRepository.getDefault(any()) } returns null
        coEvery { connectionFactory.imapParamsFor(any()) } returns imapParams()
        coEvery { imapClient.fetchForReply(any(), "INBOX", "2") } returns ReplyContext(
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
        assertEquals("boss@example.org", draft.captured.toAddresses)
        assertEquals("Re: Plan", draft.captured.subject)
        // The reply carries an HTML alternative with the quote rendered as a blockquote.
        assertTrue(draft.captured.bodyHtml?.contains("<blockquote>") == true, "html=${draft.captured.bodyHtml}")
    }

    @Test
    fun `buildReplyDraft bakes the account default signature above the quote`() = runTest {
        val id = "acct:INBOX:3"
        coEvery { messageDao.getById(id) } returns messageEntity(id, "INBOX")
        coEvery { accountDao.getById("acct") } returns accountEntity()
        coEvery { accountSettingsRepository.get(any()) } returns AccountSettings("acct")
        coEvery { signatureRepository.getDefault("acct") } returns org.libremail.domain.model.Signature(
            id = "acct:sig",
            accountId = "acct",
            name = "Signature",
            html = "Regards, Ada",
            isDefault = true,
        )
        coEvery { connectionFactory.imapParamsFor(any()) } returns imapParams()
        coEvery { imapClient.fetchForReply(any(), "INBOX", "3") } returns ReplyContext(
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

        repository.buildReplyDraft(id, ReplyMode.REPLY)

        val body = draft.captured.body
        // Signature is placed before (above) the quoted original.
        assertTrue(body.contains("Regards, Ada"), "body=$body")
        assertTrue(body.indexOf("Regards, Ada") < body.indexOf("> Original"), "body=$body")
    }

    @Test
    fun `downloadAttachment reuses an already cached file without re-downloading`() = runTest {
        val cache = Files.createTempDirectory("attach").toFile()
        every { context.cacheDir } returns cache
        val id = "acct:INBOX:4"
        coEvery { messageDao.getById(id) } returns messageEntity(id, "INBOX")
        coEvery { attachmentDao.getForMessage(id) } returns listOf(attachmentEntity(id, 0, "report.pdf"))
        val cached = File(cache, "attachments/acct_INBOX_4/0/report.pdf").apply {
            parentFile?.mkdirs()
            writeText("cached")
        }

        val result = repository.downloadAttachment(id, 0)

        assertEquals(cached, result.getOrNull())
        coVerify(exactly = 0) { imapClient.fetchAttachment(any(), any(), any(), any()) }
    }

    @Test
    fun `downloadAttachment downloads and caches when no file exists yet`() = runTest {
        val cache = Files.createTempDirectory("attach").toFile()
        every { context.cacheDir } returns cache
        val id = "acct:INBOX:6"
        coEvery { messageDao.getById(id) } returns messageEntity(id, "INBOX")
        coEvery { attachmentDao.getForMessage(id) } returns listOf(attachmentEntity(id, 0, "a.txt"))
        coEvery { accountDao.getById("acct") } returns accountEntity()
        coEvery { connectionFactory.imapParamsFor(any()) } returns imapParams()
        coEvery { imapClient.fetchAttachment(any(), "INBOX", "6", 0) } returns
            DownloadedAttachment("a.txt", "text/plain", "hello".toByteArray())

        val result = repository.downloadAttachment(id, 0)

        assertTrue(result.isSuccess)
        assertEquals("hello", result.getOrThrow().readText())
    }

    @Test
    fun `downloadedAttachmentParts reports only the parts whose bytes exist on disk`() = runTest {
        val cache = Files.createTempDirectory("attach").toFile()
        every { context.cacheDir } returns cache
        val id = "acct:INBOX:8"
        coEvery { attachmentDao.getForMessage(id) } returns listOf(
            attachmentEntity(id, 0, "have.bin"),
            attachmentEntity(id, 1, "missing.bin"),
        )
        File(cache, "attachments/acct_INBOX_8/0/have.bin").apply {
            parentFile?.mkdirs()
            writeText("x")
        }

        assertEquals(setOf(0), repository.downloadedAttachmentParts(id))
    }

    @Test
    fun `inlineImages resolves cid parts to their cached bytes and excludes real attachments`() = runTest {
        val cache = Files.createTempDirectory("attach").toFile()
        every { context.cacheDir } returns cache
        val id = "acct:INBOX:30"
        coEvery { messageDao.getById(id) } returns messageEntity(id, "INBOX")
        coEvery { attachmentDao.getForMessage(id) } returns listOf(
            AttachmentEntity(id, 0, "logo.png", "image/png", 4, contentId = "logo1"),
            AttachmentEntity(id, 1, "invoice.pdf", "application/pdf", 10, contentId = null),
        )
        // The inline part's bytes are already cached, so no network fetch is needed.
        File(cache, "attachments/acct_INBOX_30/0/logo.png").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(9, 8, 7))
        }

        val images = repository.inlineImages(id)

        assertEquals(1, images.size)
        assertEquals("logo1", images.first().contentId)
        assertEquals("image/png", images.first().mimeType)
        assertTrue(images.first().bytes.contentEquals(byteArrayOf(9, 8, 7)))
        // The ordinary attachment (contentId == null) must never be pulled in as an inline image.
        coVerify(exactly = 0) { imapClient.fetchAttachment(any(), any(), any(), any()) }
    }

    @Test
    fun `prefetchMessage caches the body and downloads attachments`() = runTest {
        val cache = Files.createTempDirectory("attach").toFile()
        every { context.cacheDir } returns cache
        val id = "acct:INBOX:10"
        coEvery { messageDao.getById(id) } returns messageEntity(id, "INBOX") // bodyFetched = false
        coEvery { accountDao.getById("acct") } returns accountEntity()
        coEvery { connectionFactory.imapParamsFor(any()) } returns imapParams()
        coEvery { imapClient.fetchBodyPeek(any(), "INBOX", "10") } returns
            MessageContent(
                "Body",
                isHtml = false,
                attachments = listOf(AttachmentPart(0, "f.bin", "application/octet-stream", 3)),
            )
        coEvery { messageDao.updateBody(id, "Body", false, any()) } just Runs
        coEvery { attachmentDao.getForMessage(id) } returns listOf(attachmentEntity(id, 0, "f.bin"))
        coEvery { imapClient.fetchAttachment(any(), "INBOX", "10", 0) } returns
            DownloadedAttachment("f.bin", "application/octet-stream", "abc".toByteArray())

        val result = repository.prefetchMessage(id)

        assertTrue(result.isSuccess)
        coVerify { messageDao.updateBody(id, "Body", false, any()) }
        coVerify { imapClient.fetchAttachment(any(), "INBOX", "10", 0) }
    }

    @Test
    fun `archive resolves a separate destination folder for each account`() = runTest {
        val id1 = "acct:INBOX:1"
        val id2 = "acct2:INBOX:1"
        coEvery { messageDao.getById(id1) } returns messageEntity(id1, "INBOX", accountId = "acct")
        coEvery { messageDao.getById(id2) } returns messageEntity(id2, "INBOX", accountId = "acct2")
        coEvery { messageDao.deleteByIds(any()) } just Runs
        coEvery { accountDao.getById("acct") } returns accountEntity(id = "acct")
        coEvery { accountDao.getById("acct2") } returns accountEntity(id = "acct2", email = "bob@example.org")
        coEvery { connectionFactory.imapParamsFor(any()) } returns imapParams()
        coEvery { folderDao.getForAccountOnce("acct") } returns listOf(folderEntity("Archive", "ARCHIVE"))
        coEvery { folderDao.getForAccountOnce("acct2") } returns listOf(folderEntity("All Mail", "ARCHIVE"))

        val result = repository.archive(listOf(id1, id2))

        assertTrue(result.isSuccess)
        coVerify { imapClient.moveMessages(any(), "INBOX", listOf("1"), "Archive") }
        coVerify { imapClient.moveMessages(any(), "INBOX", listOf("1"), "All Mail") }
    }

    @Test
    fun `prefetchMessage skips the body fetch when it is already cached`() = runTest {
        val cache = Files.createTempDirectory("attach").toFile()
        every { context.cacheDir } returns cache
        val id = "acct:INBOX:12"
        coEvery { messageDao.getById(id) } returns messageEntity(id, "INBOX", bodyFetched = true)
        coEvery { accountDao.getById("acct") } returns accountEntity()
        coEvery { connectionFactory.imapParamsFor(any()) } returns imapParams()
        coEvery { attachmentDao.getForMessage(id) } returns listOf(attachmentEntity(id, 0, "f.bin"))
        coEvery { imapClient.fetchAttachment(any(), "INBOX", "12", 0) } returns
            DownloadedAttachment("f.bin", "application/octet-stream", "x".toByteArray())

        repository.prefetchMessage(id)

        coVerify(exactly = 0) { imapClient.fetchBodyPeek(any(), any(), any()) }
        coVerify { imapClient.fetchAttachment(any(), "INBOX", "12", 0) }
    }

    @Test
    fun `archive refreshes folders once and retries when the archive folder is initially missing`() = runTest {
        val id = "acct:INBOX:14"
        coEvery { messageDao.getById(id) } returns messageEntity(id, "INBOX")
        coEvery { messageDao.deleteByIds(any()) } just Runs
        coEvery { accountDao.getById("acct") } returns accountEntity()
        coEvery { connectionFactory.imapParamsFor(any()) } returns imapParams()
        coEvery { folderDao.getForAccountOnce("acct") } returnsMany listOf(
            listOf(folderEntity("INBOX", "INBOX")), // cold cache: no archive yet
            listOf(folderEntity("INBOX", "INBOX"), folderEntity("Archive", "ARCHIVE")), // after a refresh
        )
        coEvery { folderDao.replaceForAccount(any(), any()) } just Runs
        coEvery { imapClient.listFolders(any()) } returns emptyList()

        val result = repository.archive(listOf(id))

        assertTrue(result.isSuccess)
        coVerify { imapClient.moveMessages(any(), "INBOX", listOf("14"), "Archive") }
    }

    @Test
    fun `reportSpam prefers the special-use spam folder when the user folder is listed first`() = runTest {
        val id = "acct:INBOX:16"
        coEvery { messageDao.getById(id) } returns messageEntity(id, "INBOX")
        coEvery { messageDao.deleteByIds(any()) } just Runs
        coEvery { accountDao.getById("acct") } returns accountEntity()
        coEvery { connectionFactory.imapParamsFor(any()) } returns imapParams()
        // A user label "Spam" (role from its name) is LISTed before Gmail's built-in \Junk folder.
        coEvery { folderDao.getForAccountOnce("acct") } returns listOf(
            folderEntity("Spam", "SPAM"),
            folderEntity("[Gmail]/Spam", "SPAM", specialUse = true),
        )

        val result = repository.reportSpam(listOf(id))

        assertTrue(result.isSuccess)
        coVerify { imapClient.moveMessages(any(), "INBOX", listOf("16"), "[Gmail]/Spam") }
        coVerify(exactly = 0) { imapClient.moveMessages(any(), any(), any(), "Spam") }
    }

    @Test
    fun `reportSpam prefers the special-use spam folder when it is listed first`() = runTest {
        val id = "acct:INBOX:18"
        coEvery { messageDao.getById(id) } returns messageEntity(id, "INBOX")
        coEvery { messageDao.deleteByIds(any()) } just Runs
        coEvery { accountDao.getById("acct") } returns accountEntity()
        coEvery { connectionFactory.imapParamsFor(any()) } returns imapParams()
        coEvery { folderDao.getForAccountOnce("acct") } returns listOf(
            folderEntity("[Gmail]/Spam", "SPAM", specialUse = true),
            folderEntity("Spam", "SPAM"),
        )

        val result = repository.reportSpam(listOf(id))

        assertTrue(result.isSuccess)
        coVerify { imapClient.moveMessages(any(), "INBOX", listOf("18"), "[Gmail]/Spam") }
        coVerify(exactly = 0) { imapClient.moveMessages(any(), any(), any(), "Spam") }
    }

    @Test
    fun `reportSpam keeps the first listed folder when no special-use folder holds the role`() = runTest {
        val id = "acct:INBOX:20"
        coEvery { messageDao.getById(id) } returns messageEntity(id, "INBOX")
        coEvery { messageDao.deleteByIds(any()) } just Runs
        coEvery { accountDao.getById("acct") } returns accountEntity()
        coEvery { connectionFactory.imapParamsFor(any()) } returns imapParams()
        // No SPECIAL-USE advertised (common outside the big providers): LIST order still decides.
        coEvery { folderDao.getForAccountOnce("acct") } returns listOf(
            folderEntity("Junk", "SPAM"),
            folderEntity("Spam", "SPAM"),
        )

        val result = repository.reportSpam(listOf(id))

        assertTrue(result.isSuccess)
        coVerify { imapClient.moveMessages(any(), "INBOX", listOf("20"), "Junk") }
    }

    private fun folderEntity(fullName: String, role: String, specialUse: Boolean = false) = FolderEntity(
        accountId = "acct",
        fullName = fullName,
        displayName = fullName.substringAfterLast('/'),
        role = role,
        selectable = true,
        sortOrder = 0,
        specialUse = specialUse,
    )

    private fun attachmentEntity(messageId: String, partIndex: Int, filename: String) =
        AttachmentEntity(messageId, partIndex, filename, "application/octet-stream", 10L)

    private fun messageEntity(id: String, folder: String, accountId: String = "acct", bodyFetched: Boolean = false) =
        MessageEntity(
            id = id,
            accountId = accountId,
            sender = "Ada",
            senderEmail = "ada@example.org",
            subject = "Hi",
            snippet = "snippet",
            body = "",
            timestampMillis = 1_000L,
            isRead = false,
            isStarred = false,
            folder = folder,
            bodyFetched = bodyFetched,
        )

    private fun messageSummary(id: String, folder: String, accountId: String = "acct", bodyFetched: Boolean = false) =
        MessageSummary(
            id = id,
            accountId = accountId,
            sender = "Ada",
            senderEmail = "ada@example.org",
            subject = "Hi",
            snippet = "snippet",
            timestampMillis = 1_000L,
            isRead = false,
            isStarred = false,
            folder = folder,
            inInbox = true,
            bodyFetched = bodyFetched,
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

    /** Serves a fixed set of summaries as a single page, standing in for Room's generated source. */
    private class FakeSummaryPagingSource(private val rows: List<MessageSummary>) :
        PagingSource<Int, MessageSummary>() {
        override fun getRefreshKey(state: PagingState<Int, MessageSummary>): Int? = null
        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, MessageSummary> =
            LoadResult.Page(data = rows, prevKey = null, nextKey = null)
    }
}

/** Bounded real-time wait for the background SEEN-flag push to have started (see `pushSeenFlagInBackground`). */
private const val FLAG_PUSH_AWAIT_TIMEOUT_MS = 2_000L

/** Bounded real-time wait for a second (retried) SEEN-flag push attempt to land. */
private const val FLAG_PUSH_RETRY_VERIFY_TIMEOUT_MS = 5_000L
