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
import org.libremail.data.local.dao.AccountSettingsDao
import org.libremail.data.local.entity.AccountSettingsEntity
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountSettingsRepositoryTest {

    private val dao = mockk<AccountSettingsDao>()
    private val repository = AccountSettingsRepository(dao)

    @Test
    fun `get returns defaults when no row exists`() = runTest {
        coEvery { dao.get("acct") } returns null

        val settings = repository.get("acct")

        assertEquals("acct", settings.accountId)
        assertEquals("", settings.signature)
        assertTrue(settings.signatureEnabled)
        assertTrue(settings.notificationsEnabled)
    }

    @Test
    fun `setSignature reads, modifies, and writes the row`() = runTest {
        coEvery { dao.get("acct") } returns
            AccountSettingsEntity("acct", signature = "old", signatureEnabled = true, notificationsEnabled = false)
        val saved = slot<AccountSettingsEntity>()
        coEvery { dao.upsert(capture(saved)) } just Runs

        repository.setSignature("acct", "new")

        assertEquals("new", saved.captured.signature)
        // Untouched fields are preserved.
        assertEquals(false, saved.captured.notificationsEnabled)
    }

    @Test
    fun `ensureDefaults inserts a default row when none exists`() = runTest {
        coEvery { dao.get("acct") } returns null
        val saved = slot<AccountSettingsEntity>()
        coEvery { dao.upsert(capture(saved)) } just Runs

        repository.ensureDefaults("acct")

        assertEquals("acct", saved.captured.accountId)
        coVerify(exactly = 1) { dao.upsert(any()) }
    }

    @Test
    fun `ensureDefaults does nothing when a row already exists`() = runTest {
        coEvery { dao.get("acct") } returns AccountSettingsEntity("acct")

        repository.ensureDefaults("acct")

        coVerify(exactly = 0) { dao.upsert(any()) }
    }
}
