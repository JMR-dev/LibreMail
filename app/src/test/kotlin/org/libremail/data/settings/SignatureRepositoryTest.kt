// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.settings

import app.cash.turbine.test
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.libremail.data.local.dao.SignatureDao
import org.libremail.data.local.entity.SignatureEntity
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignatureRepositoryTest {

    private val dao = mockk<SignatureDao>(relaxed = true)
    private val repository = SignatureRepository(dao)

    private fun entity(id: String, isDefault: Boolean) =
        SignatureEntity(id, accountId = "acct", name = "N", contentHtml = "<p>x</p>", isDefault = isDefault)

    @Test
    fun `the first signature for an account becomes its default`() = runTest {
        coEvery { dao.countForAccount("acct") } returns 0
        val saved = slot<SignatureEntity>()
        coEvery { dao.upsert(capture(saved)) } just Runs

        repository.create("acct", "Work", "<p>hi</p>")

        assertTrue(saved.captured.isDefault)
    }

    @Test
    fun `later signatures are not made default`() = runTest {
        coEvery { dao.countForAccount("acct") } returns 2
        val saved = slot<SignatureEntity>()
        coEvery { dao.upsert(capture(saved)) } just Runs

        repository.create("acct", "Personal", "<p>hey</p>")

        assertFalse(saved.captured.isDefault)
    }

    @Test
    fun `deleting the default promotes the first remaining signature`() = runTest {
        coEvery { dao.getById("s1") } returns entity("s1", isDefault = true)
        coEvery { dao.firstForAccount("acct") } returns entity("s2", isDefault = false)

        repository.delete("s1")

        coVerify { dao.delete("s1") }
        coVerify { dao.markDefault("s2") }
    }

    @Test
    fun `deleting a non-default signature promotes nothing`() = runTest {
        coEvery { dao.getById("s2") } returns entity("s2", isDefault = false)

        repository.delete("s2")

        coVerify { dao.delete("s2") }
        coVerify(exactly = 0) { dao.firstForAccount(any()) }
        coVerify(exactly = 0) { dao.markDefault(any()) }
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
