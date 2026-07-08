// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.settings

import app.cash.turbine.test
import io.mockk.CapturingSlot
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
import org.libremail.data.local.dao.AccountSettingsDao
import org.libremail.data.local.entity.AccountSettingsEntity
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountSettingsRepositoryTest {

    private val dao = mockk<AccountSettingsDao>()
    private val repository = AccountSettingsRepository(dao)

    /**
     * Stubs the DAO's single-transaction read-modify-write (issue #313) to apply the repository's
     * transform against [current] (the stored row, or null) and capture the entity it would persist — so
     * these setter tests still assert the transformed row directly, while AccountSettingsDaoTest covers
     * the real transaction.
     */
    private fun captureUpdate(current: AccountSettingsEntity?): CapturingSlot<AccountSettingsEntity> {
        val saved = slot<AccountSettingsEntity>()
        coEvery { dao.readModifyWrite(any(), any()) } coAnswers {
            saved.captured = secondArg<(AccountSettingsEntity?) -> AccountSettingsEntity>().invoke(current)
        }
        return saved
    }

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
        val saved = captureUpdate(
            AccountSettingsEntity("acct", signature = "old", signatureEnabled = true, notificationsEnabled = false),
        )

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

    @Test
    fun `observe maps the stored row to the domain model`() = runTest {
        every { dao.observe("acct") } returns flowOf(
            AccountSettingsEntity("acct", signature = "sig", signatureEnabled = false, notificationsEnabled = true),
        )

        repository.observe("acct").test {
            val settings = awaitItem()
            assertEquals("sig", settings.signature)
            assertFalse(settings.signatureEnabled)
            assertTrue(settings.notificationsEnabled)
            awaitComplete()
        }
    }

    @Test
    fun `observe emits defaults for an account with no stored row`() = runTest {
        every { dao.observe("acct") } returns flowOf(null)

        repository.observe("acct").test {
            val settings = awaitItem()
            assertEquals("acct", settings.accountId)
            assertEquals("", settings.signature)
            assertTrue(settings.signatureEnabled)
            awaitComplete()
        }
    }

    @Test
    fun `setSignatureEnabled reads, modifies, and writes the row`() = runTest {
        val saved = captureUpdate(
            AccountSettingsEntity("acct", signature = "keep", signatureEnabled = true, notificationsEnabled = true),
        )

        repository.setSignatureEnabled("acct", false)

        assertFalse(saved.captured.signatureEnabled)
        // Untouched fields are preserved.
        assertEquals("keep", saved.captured.signature)
    }

    @Test
    fun `setNotificationsEnabled reads, modifies, and writes the row`() = runTest {
        val saved = captureUpdate(AccountSettingsEntity("acct", notificationsEnabled = true))

        repository.setNotificationsEnabled("acct", false)

        assertFalse(saved.captured.notificationsEnabled)
    }

    @Test
    fun `setRetentionCount clamps a negative override to zero`() = runTest {
        val saved = captureUpdate(AccountSettingsEntity("acct"))

        repository.setRetentionCount("acct", -5)

        assertEquals(0, saved.captured.retentionCount)
    }

    @Test
    fun `setRetentionCount preserves null as inherit-the-global-default`() = runTest {
        val saved = captureUpdate(AccountSettingsEntity("acct", retentionCount = 10))

        repository.setRetentionCount("acct", null)

        assertNull(saved.captured.retentionCount)
    }

    @Test
    fun `setRetentionMonths clamps a negative override to zero`() = runTest {
        val saved = captureUpdate(AccountSettingsEntity("acct"))

        repository.setRetentionMonths("acct", -3)

        assertEquals(0, saved.captured.retentionMonths)
    }

    @Test
    fun `setRetentionMonths keeps a positive override as given`() = runTest {
        val saved = captureUpdate(AccountSettingsEntity("acct"))

        repository.setRetentionMonths("acct", 6)

        assertEquals(6, saved.captured.retentionMonths)
    }

    @Test
    fun `a setter transforms the account defaults when no row exists yet`() = runTest {
        // The DAO hands the transform a null stored row for a never-configured account; the repository
        // must transform from the account's defaults so the "not configured = defaults" contract holds.
        val saved = captureUpdate(current = null)

        repository.setSignature("acct", "first")

        assertEquals("acct", saved.captured.accountId)
        assertEquals("first", saved.captured.signature)
        assertTrue(saved.captured.signatureEnabled, "defaults carry through the transform")
    }
}
