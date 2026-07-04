// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.attachment

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.libremail.data.local.dao.DraftDao
import org.libremail.data.local.dao.OutboxDao
import org.libremail.data.local.entity.DraftEntity
import org.libremail.data.local.entity.OutboxEntity
import kotlin.test.assertEquals

/**
 * The release decision that [AttachmentUriGrants] runs after a draft/outbox row is deleted: a picked
 * URI's persistable grant is released only when no *remaining* draft or outbox row still references
 * it. The pure [unreferencedUris] decision is pinned first; the instance-method tests then wire that
 * decision to the DAOs and the (mocked) content resolver, covering the empty-set short-circuit, the
 * still-referenced guard against drafts and outbox rows alike, and the swallowed SecurityException
 * from releasing a grant the app never actually held.
 */
class AttachmentUriGrantsTest {

    @Test
    fun `releases a uri referenced by no remaining row`() {
        assertEquals(
            listOf("content://a"),
            unreferencedUris(candidates = listOf("content://a"), referenced = emptySet()),
        )
    }

    @Test
    fun `keeps a uri still referenced by another live draft or outbox row`() {
        // content://shared is attached to a second draft that is still around, so its grant must stay.
        assertEquals(
            listOf("content://gone"),
            unreferencedUris(
                candidates = listOf("content://gone", "content://shared"),
                referenced = setOf("content://shared"),
            ),
        )
    }

    @Test
    fun `deduplicates candidate uris`() {
        assertEquals(
            listOf("content://a"),
            unreferencedUris(candidates = listOf("content://a", "content://a"), referenced = emptySet()),
        )
    }

    @Test
    fun `releases nothing when every candidate is still referenced`() {
        assertEquals(
            emptyList(),
            unreferencedUris(candidates = listOf("content://a"), referenced = setOf("content://a")),
        )
    }

    // --- releaseUnreferenced: wiring the decision to the DAOs and the content resolver --------------

    private val draftDao = mockk<DraftDao>()
    private val outboxDao = mockk<OutboxDao>()
    private val resolver = mockk<ContentResolver>(relaxed = true)
    private val context = mockk<Context>()
    private val grants = AttachmentUriGrants(context, draftDao, outboxDao)

    @After
    fun tearDown() = unmockkAll()

    private fun draft(vararg uris: String) = DraftEntity(
        id = "draft-${uris.joinToString()}",
        accountId = "a",
        toAddresses = "",
        ccAddresses = "",
        subject = "",
        body = "",
        updatedAt = 0L,
        attachments = attachmentsJson(*uris),
    )

    private fun outbox(vararg uris: String) = OutboxEntity(
        id = "outbox-${uris.joinToString()}",
        accountId = "a",
        toAddresses = "",
        ccAddresses = "",
        subject = "",
        body = "",
        createdAt = 0L,
        attachments = attachmentsJson(*uris),
    )

    private fun attachmentsJson(vararg uris: String): String =
        if (uris.isEmpty()) "" else uris.joinToString(",", "[", "]") { """{"uri":"$it","name":"n"}""" }

    private fun stubResolver() {
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } returns mockk(relaxed = true)
        every { context.contentResolver } returns resolver
    }

    @Test
    fun `releaseUnreferenced short-circuits on an empty uri set without querying the daos`() = runTest {
        grants.releaseUnreferenced(emptyList())

        coVerify(exactly = 0) { draftDao.getAll() }
        coVerify(exactly = 0) { outboxDao.getAll() }
    }

    @Test
    fun `releaseUnreferenced releases only the uris no remaining draft or outbox row references`() = runTest {
        stubResolver()
        // "content://shared" is still attached to a live draft; "content://gone" is referenced by nobody.
        coEvery { draftDao.getAll() } returns listOf(draft("content://shared"))
        coEvery { outboxDao.getAll() } returns emptyList()

        grants.releaseUnreferenced(listOf("content://gone", "content://shared"))

        // The still-referenced uri is kept (never parsed for release); only the orphan is released.
        verify(exactly = 1) { Uri.parse("content://gone") }
        verify(exactly = 0) { Uri.parse("content://shared") }
        verify(exactly = 1) {
            resolver.releasePersistableUriPermission(any(), eq(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
    }

    @Test
    fun `releaseUnreferenced keeps a uri still referenced by a queued outbox row`() = runTest {
        stubResolver()
        coEvery { draftDao.getAll() } returns emptyList()
        coEvery { outboxDao.getAll() } returns listOf(outbox("content://queued"))

        grants.releaseUnreferenced(listOf("content://queued"))

        verify(exactly = 0) { resolver.releasePersistableUriPermission(any(), any()) }
    }

    @Test
    fun `releaseUnreferenced swallows the SecurityException from releasing a grant it never held`() = runTest {
        stubResolver()
        coEvery { draftDao.getAll() } returns emptyList()
        coEvery { outboxDao.getAll() } returns emptyList()
        every { resolver.releasePersistableUriPermission(any(), any()) } throws SecurityException("no grant held")

        // Releasing a never-persisted uri throws SecurityException inside runCatching; it must not escape.
        grants.releaseUnreferenced(listOf("content://never-held"))

        verify { resolver.releasePersistableUriPermission(any(), any()) }
    }
}
