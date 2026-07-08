// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.settings

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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.data.local.dao.SignatureDao
import org.libremail.data.local.entity.SignatureEntity
import org.libremail.reporting.AppLog
import org.libremail.reporting.RingLogBuffer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignatureRepositoryTest {

    private val dao = mockk<SignatureDao>(relaxed = true)
    private val repository = SignatureRepository(dao)

    // delete() breadcrumbs a promotion via AppLog; android.util.Log is an unmocked stub in plain JVM
    // tests, so mock it class-wide (mirrors MailRepositoryImplTest).
    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
    }

    @After
    fun tearDown() = unmockkAll()

    private fun entity(id: String, isDefault: Boolean) =
        SignatureEntity(id, accountId = "acct", name = "N", contentHtml = "<p>x</p>", isDefault = isDefault)

    @Test
    fun `create routes through the atomic first-default insert with the given fields`() = runTest {
        // The first-becomes-default decision now lives in the DAO transaction (issue #313); the repository
        // just forwards the new row (isDefault a placeholder) and returns its generated id.
        val saved = slot<SignatureEntity>()
        coEvery { dao.insertMakingFirstDefault(capture(saved)) } just Runs

        val id = repository.create("acct", "Work", "<p>hi</p>")

        assertEquals("acct", saved.captured.accountId)
        assertEquals("Work", saved.captured.name)
        assertEquals("<p>hi</p>", saved.captured.contentHtml)
        assertEquals(id, saved.captured.id)
        assertFalse(saved.captured.isDefault, "the DAO decides the default flag, not the repository")
    }

    @Test
    fun `delete routes through the atomic delete-and-promote`() = runTest {
        coEvery { dao.deletePromotingDefault("s1") } returns null

        repository.delete("s1")

        coVerify { dao.deletePromotingDefault("s1") }
    }

    @Test
    fun `deleting a default that promotes a replacement logs a breadcrumb`() = runTest {
        val buffer = RingLogBuffer()
        AppLog.install(buffer)
        coEvery { dao.deletePromotingDefault("s1") } returns "s2"

        repository.delete("s1")

        assertTrue(
            buffer.snapshot().any { it.message.contains("promoted a replacement default signature") },
            "the promotion is recorded for a debug report",
        )
    }

    @Test
    fun `deleting without a promotion logs nothing`() = runTest {
        val buffer = RingLogBuffer()
        AppLog.install(buffer)
        coEvery { dao.deletePromotingDefault("s2") } returns null

        repository.delete("s2")

        assertFalse(
            buffer.snapshot().any { it.message.contains("promoted a replacement default") },
        )
    }

    @Test
    fun `setDefault delegates to the dao's atomic swap`() = runTest {
        repository.setDefault("acct", "s1")
        coVerify { dao.setDefault("acct", "s1") }
    }

    @Test
    fun `observeForAccount maps entity rows to domain signatures`() = runTest {
        every { dao.observeForAccount("acct") } returns flowOf(
            listOf(entity("s1", isDefault = true), entity("s2", isDefault = false)),
        )

        repository.observeForAccount("acct").test {
            val signatures = awaitItem()
            assertEquals(listOf("s1", "s2"), signatures.map { it.id })
            assertEquals("acct", signatures.first().accountId)
            assertTrue(signatures.first().isDefault)
            awaitComplete()
        }
    }

    @Test
    fun `get maps the stored row to a domain signature`() = runTest {
        coEvery { dao.getById("s1") } returns entity("s1", isDefault = true)

        val signature = repository.get("s1")

        assertEquals("s1", signature?.id)
        assertEquals("<p>x</p>", signature?.html)
        assertTrue(signature?.isDefault == true)
    }

    @Test
    fun `get returns null for an unknown id`() = runTest {
        coEvery { dao.getById("missing") } returns null

        assertNull(repository.get("missing"))
    }

    @Test
    fun `getDefault returns the account's default signature`() = runTest {
        coEvery { dao.getDefault("acct") } returns entity("s1", isDefault = true)

        assertEquals("s1", repository.getDefault("acct")?.id)
    }

    @Test
    fun `getDefault returns null when the account has no default`() = runTest {
        coEvery { dao.getDefault("acct") } returns null

        assertNull(repository.getDefault("acct"))
    }

    @Test
    fun `update rewrites the name and html of an existing signature`() = runTest {
        coEvery { dao.getById("s1") } returns entity("s1", isDefault = true)
        val saved = slot<SignatureEntity>()
        coEvery { dao.upsert(capture(saved)) } just Runs

        repository.update("s1", "Renamed", "<p>new</p>")

        assertEquals("Renamed", saved.captured.name)
        assertEquals("<p>new</p>", saved.captured.contentHtml)
        // Editing a signature leaves its default flag and account untouched.
        assertTrue(saved.captured.isDefault)
        assertEquals("acct", saved.captured.accountId)
    }

    @Test
    fun `update is a no-op for an unknown id`() = runTest {
        coEvery { dao.getById("missing") } returns null

        repository.update("missing", "X", "<p>y</p>")

        coVerify(exactly = 0) { dao.upsert(any()) }
    }
}
