// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.repository

import android.content.Context
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.libremail.data.attachment.AttachmentUriGrants
import org.libremail.data.local.dao.DraftDao
import org.libremail.data.local.dao.OutboxDao
import org.libremail.data.local.entity.DraftEntity
import org.libremail.data.local.entity.OutboxEntity
import org.libremail.data.sync.InteractiveImapGate
import java.nio.file.Files

/**
 * Deleting a draft, or cancelling a queued outbox message, must hand the removed row's picked-URI
 * grants to [AttachmentUriGrants] so they can be released once nothing references them (post-batch
 * security review). The release itself is decided/executed in [AttachmentUriGrants]; here we pin that
 * the repository captures the right URIs and calls it *after* the row is gone.
 */
class MailRepositoryGrantsTest {

    private val draftDao = mockk<DraftDao>()
    private val outboxDao = mockk<OutboxDao>()
    private val attachmentUriGrants = mockk<AttachmentUriGrants>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)

    private val repository = MailRepositoryImpl(
        context = context,
        messageDao = mockk(relaxed = true),
        accountDao = mockk(relaxed = true),
        attachmentDao = mockk(relaxed = true),
        outboxDao = outboxDao,
        draftDao = draftDao,
        folderDao = mockk(relaxed = true),
        imapClient = mockk(relaxed = true),
        connectionFactory = mockk(relaxed = true),
        sendScheduler = mockk(relaxed = true),
        accountSettingsRepository = mockk(relaxed = true),
        signatureRepository = mockk(relaxed = true),
        attachmentUriGrants = attachmentUriGrants,
        interactiveGate = InteractiveImapGate(),
    )

    @Test
    fun `deleteDraft releases the deleted draft's URI grants after removing the row`() = runTest {
        coEvery { draftDao.getById("d1") } returns draftEntity(
            "d1",
            """[{"uri":"content://pick/1","name":"a.png"}]""",
        )
        coEvery { draftDao.delete("d1") } just Runs

        repository.deleteDraft("d1")

        // Order matters: the row must be gone before the "still referenced?" check runs, so a draft
        // doesn't keep its own grant alive on the way out.
        coVerifyOrder {
            draftDao.delete("d1")
            attachmentUriGrants.releaseUnreferenced(listOf("content://pick/1"))
        }
    }

    @Test
    fun `deleteDraft of an attachment-less draft releases nothing`() = runTest {
        coEvery { draftDao.getById("d2") } returns null // already gone: no attachments to release
        coEvery { draftDao.delete("d2") } just Runs

        repository.deleteDraft("d2")

        coVerify { attachmentUriGrants.releaseUnreferenced(emptyList()) }
    }

    @Test
    fun `cancelOutboxMessage releases the cancelled message's URI grants`() = runTest {
        every { context.cacheDir } returns Files.createTempDirectory("outbox").toFile()
        coEvery { outboxDao.getById("o1") } returns outboxEntity(
            "o1",
            """[{"uri":"content://pick/2","name":"b.png"}]""",
        )
        coEvery { outboxDao.delete("o1") } just Runs

        repository.cancelOutboxMessage("o1")

        coVerify { attachmentUriGrants.releaseUnreferenced(listOf("content://pick/2")) }
    }

    private fun draftEntity(id: String, attachmentsJson: String) = DraftEntity(
        id = id,
        accountId = "acct",
        toAddresses = "",
        ccAddresses = "",
        bccAddresses = "",
        subject = "",
        body = "",
        updatedAt = 0L,
        attachments = attachmentsJson,
    )

    private fun outboxEntity(id: String, attachmentsJson: String) = OutboxEntity(
        id = id,
        accountId = "acct",
        toAddresses = "bob@example.org",
        ccAddresses = "",
        subject = "Hi",
        body = "body",
        createdAt = 0L,
        attachments = attachmentsJson,
    )
}
