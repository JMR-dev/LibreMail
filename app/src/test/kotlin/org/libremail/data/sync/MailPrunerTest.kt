// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import android.content.Context
import android.util.Log
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
import org.libremail.data.local.dao.AccountDao
import org.libremail.data.local.dao.MessageDao
import org.libremail.data.local.entity.AccountEntity
import org.libremail.data.local.entity.ServerConfigEmbedded
import org.libremail.data.settings.AccountSettingsRepository
import org.libremail.data.settings.AppSettings
import org.libremail.data.settings.SettingsRepository
import org.libremail.domain.model.AccountSettings
import org.libremail.reporting.AppLog
import org.libremail.reporting.RingLogBuffer
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Retention pruning (issue #13) is purely LOCAL: [MailPruner] has no IMAP collaborator at all, so it
 * is structurally incapable of issuing a server delete — these tests assert it removes exactly the
 * over-limit local rows (by count and by age) and nothing when retention is unlimited.
 */
class MailPrunerTest {

    private val account = AccountEntity(
        id = "acct",
        email = "a@example.org",
        displayName = "A",
        authType = "PASSWORD_IMAP",
        imap = ServerConfigEmbedded("imap.example.org", 993, "SSL_TLS"),
        smtp = ServerConfigEmbedded("smtp.example.org", 465, "SSL_TLS"),
    )

    // issue #329: AppLog breadcrumbs — a fresh buffer per test (a new instance per @Test, per JUnit4).
    private val logBuffer = RingLogBuffer()

    @Before
    fun setUp() {
        // `android.util.Log` is a no-op stub under plain JVM unit tests, so it is statically mocked here
        // for the whole class, mirroring org.libremail.reporting.AppLogTest — every test now exercises
        // real prune code, which breadcrumbs through AppLog.
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.d(any(), any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        AppLog.install(logBuffer)
    }

    @After
    fun tearDown() = unmockkAll()

    private fun pruner(global: AppSettings, accountSettings: AccountSettings, messageDao: MessageDao): MailPruner {
        val accountDao = mockk<AccountDao>()
        coEvery { accountDao.getAll() } returns listOf(account)
        val settingsRepository = mockk<SettingsRepository>()
        every { settingsRepository.settings } returns flowOf(global)
        val accountSettingsRepository = mockk<AccountSettingsRepository>()
        coEvery { accountSettingsRepository.get("acct") } returns accountSettings
        val context = mockk<Context>(relaxed = true)
        every { context.cacheDir } returns File(System.getProperty("java.io.tmpdir"), "libremail-prune-test")
        return MailPruner(
            context = context,
            accountDao = accountDao,
            messageDao = messageDao,
            settingsRepository = settingsRepository,
            accountSettingsRepository = accountSettingsRepository,
            maintenanceGate = MailMaintenanceGate(),
        )
    }

    @Test
    fun `count limit prunes the over-limit rows of each folder and never the server`() = runTest {
        val messageDao = mockk<MessageDao>(relaxed = true)
        coEvery { messageDao.syncedFolders("acct") } returns listOf("INBOX", "Archive")
        coEvery { messageDao.syncedIdsBeyondCountInFolder("acct", "INBOX", 2) } returns
            listOf("acct:INBOX:1", "acct:INBOX:2")
        coEvery { messageDao.syncedIdsBeyondCountInFolder("acct", "Archive", 2) } returns listOf("acct:Archive:9")
        val deleted = slot<List<String>>()
        coEvery { messageDao.deleteByIds(capture(deleted)) } just Runs

        val removed = pruner(
            global = AppSettings(retentionCount = 2, retentionMonths = 0),
            accountSettings = AccountSettings("acct"),
            messageDao = messageDao,
        ).prune()

        assertEquals(setOf("acct:INBOX:1", "acct:INBOX:2", "acct:Archive:9"), deleted.captured.toSet())
        assertEquals(3, removed)
        // Age was unlimited, so the age query is never issued.
        coVerify(exactly = 0) { messageDao.syncedIdsOlderThan(any(), any()) }
    }

    @Test
    fun `age limit prunes rows older than the cutoff across all folders`() = runTest {
        val messageDao = mockk<MessageDao>(relaxed = true)
        val cutoff = slot<Long>()
        coEvery { messageDao.syncedIdsOlderThan(eq("acct"), capture(cutoff)) } returns
            listOf("acct:INBOX:1", "acct:Sent:4")
        val deleted = slot<List<String>>()
        coEvery { messageDao.deleteByIds(capture(deleted)) } just Runs

        val now = 1_800_000_000_000L
        val removed = pruner(
            global = AppSettings(retentionCount = 0, retentionMonths = 6),
            accountSettings = AccountSettings("acct"),
            messageDao = messageDao,
        ).prune(nowMillis = now)

        assertEquals(setOf("acct:INBOX:1", "acct:Sent:4"), deleted.captured.toSet())
        assertEquals(2, removed)
        assertTrue(cutoff.captured < now, "age cutoff must be strictly before now")
        // Count was unlimited, so no per-folder count query runs.
        coVerify(exactly = 0) { messageDao.syncedIdsBeyondCountInFolder(any(), any(), any()) }
    }

    @Test
    fun `per-account override takes precedence over the global default`() = runTest {
        val messageDao = mockk<MessageDao>(relaxed = true)
        coEvery { messageDao.syncedFolders("acct") } returns listOf("INBOX")
        coEvery { messageDao.syncedIdsBeyondCountInFolder("acct", "INBOX", 10) } returns listOf("acct:INBOX:1")
        coEvery { messageDao.deleteByIds(any()) } just Runs

        // Global default is unlimited; the account overrides count to 10, so pruning still runs at 10.
        pruner(
            global = AppSettings(retentionCount = 0, retentionMonths = 0),
            accountSettings = AccountSettings("acct", retentionCount = 10),
            messageDao = messageDao,
        ).prune()

        coVerify { messageDao.syncedIdsBeyondCountInFolder("acct", "INBOX", 10) }
    }

    @Test
    fun `keeps everything and deletes nothing when retention is unlimited`() = runTest {
        val messageDao = mockk<MessageDao>(relaxed = true)

        val removed = pruner(
            global = AppSettings(retentionCount = 0, retentionMonths = 0),
            accountSettings = AccountSettings("acct"),
            messageDao = messageDao,
        ).prune()

        assertEquals(0, removed)
        coVerify(exactly = 0) { messageDao.deleteByIds(any()) }
        coVerify(exactly = 0) { messageDao.syncedIdsOlderThan(any(), any()) }
        coVerify(exactly = 0) { messageDao.syncedIdsBeyondCountInFolder(any(), any(), any()) }
    }

    // --- issue #329: AppLog breadcrumbs ---------------------------------------------------------

    @Test
    fun `prune logs a done breadcrumb with the removed count`() = runTest {
        val messageDao = mockk<MessageDao>(relaxed = true)
        coEvery { messageDao.syncedFolders("acct") } returns listOf("INBOX", "Archive")
        coEvery { messageDao.syncedIdsBeyondCountInFolder("acct", "INBOX", 2) } returns
            listOf("acct:INBOX:1", "acct:INBOX:2")
        coEvery { messageDao.syncedIdsBeyondCountInFolder("acct", "Archive", 2) } returns listOf("acct:Archive:9")
        coEvery { messageDao.deleteByIds(any()) } just Runs

        pruner(
            global = AppSettings(retentionCount = 2, retentionMonths = 0),
            accountSettings = AccountSettings("acct"),
            messageDao = messageDao,
        ).prune()

        val entry = logBuffer.snapshot().single()
        assertEquals('I', entry.level)
        assertEquals("prune done: removed=3", entry.message)
    }

    @Test
    fun `prune logs a done breadcrumb even when nothing is removed`() = runTest {
        val messageDao = mockk<MessageDao>(relaxed = true)

        pruner(
            global = AppSettings(retentionCount = 0, retentionMonths = 0),
            accountSettings = AccountSettings("acct"),
            messageDao = messageDao,
        ).prune()

        val entry = logBuffer.snapshot().single()
        assertEquals('I', entry.level)
        assertEquals("prune done: removed=0", entry.message)
    }

    @Test
    fun `prune never logs the account email or host, even while removing rows by age and count`() = runTest {
        val messageDao = mockk<MessageDao>(relaxed = true)
        coEvery { messageDao.syncedFolders("acct") } returns listOf("INBOX")
        coEvery { messageDao.syncedIdsOlderThan(any(), any()) } returns listOf("acct:INBOX:1")
        coEvery { messageDao.syncedIdsBeyondCountInFolder("acct", "INBOX", 2) } returns listOf("acct:INBOX:2")
        coEvery { messageDao.deleteByIds(any()) } just Runs

        pruner(
            global = AppSettings(retentionCount = 2, retentionMonths = 6),
            accountSettings = AccountSettings("acct"),
            messageDao = messageDao,
        ).prune()

        logBuffer.snapshot().forEach { entry ->
            assertFalse(entry.message.contains("a@example.org"), entry.message)
            assertFalse(entry.message.contains("example.org"), entry.message)
        }
    }
}
