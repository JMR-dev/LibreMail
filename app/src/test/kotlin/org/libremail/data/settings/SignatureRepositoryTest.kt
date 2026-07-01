// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.settings

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.libremail.data.local.dao.SignatureDao
import org.libremail.data.local.entity.SignatureEntity
import kotlin.test.assertFalse
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
}
