// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import android.content.Context
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetupTest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.data.local.dao.AccountDao
import org.libremail.data.local.dao.BackfillProgressDao
import org.libremail.data.local.dao.MessageDao
import org.libremail.data.local.entity.AccountEntity
import org.libremail.data.local.entity.BackfillProgressEntity
import org.libremail.data.local.entity.MessageEntity
import org.libremail.data.local.entity.ServerConfigEmbedded
import org.libremail.data.local.toEntity
import org.libremail.data.settings.AccountSettingsRepository
import org.libremail.data.settings.AppSettings
import org.libremail.data.settings.FetchPolicy
import org.libremail.data.settings.SettingsRepository
import org.libremail.domain.model.AccountSettings
import org.libremail.domain.model.ImapConnectionParams
import org.libremail.domain.model.MailSecurity
import org.libremail.domain.repository.MailRepository
import org.libremail.mail.ImapClient
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end backfill (issue #12) against a real in-process IMAP server with in-memory DAO fakes:
 * the backfiller caches the WHOLE mailbox (far more than the 50-message foreground window), persists
 * a resumable boundary, resumes correctly after a simulated interruption, honours the retention floor
 * (issue #13 precedence), and never deletes anything.
 */
class MailBackfillerTest {

    private lateinit var greenMail: GreenMail
    private val client = ImapClient()

    private val accountEntity = AccountEntity(
        id = "acct",
        email = "alice@example.org",
        displayName = "Alice",
        authType = "PASSWORD_IMAP",
        imap = ServerConfigEmbedded("127.0.0.1", 993, "NONE"),
        smtp = ServerConfigEmbedded("127.0.0.1", 465, "NONE"),
    )

    // In-memory stand-ins for the two tables the backfiller writes.
    private val cached = mutableListOf<MessageEntity>()
    private val progress = mutableMapOf<Pair<String, String>, BackfillProgressEntity>()

    @Before
    fun setUp() {
        greenMail = GreenMail(ServerSetupTest.SMTP_IMAP)
        greenMail.start()
        greenMail.setUser("alice@example.org", "secret")
    }

    @After
    fun tearDown() = greenMail.stop()

    private fun params() = ImapConnectionParams(
        host = "127.0.0.1",
        port = greenMail.imap.port,
        security = MailSecurity.NONE,
        username = "alice@example.org",
        secret = "secret",
        useXoauth2 = false,
    )

    @Test
    fun `backfills the entire history over successive runs, far beyond the 50-message window`() = runTest {
        appendMessages(TOTAL)
        seedForegroundWindow()
        assertEquals(WINDOW, cached.size)

        val backfiller = backfiller(AccountSettings("acct"))
        // Drive it to completion; each run does a bounded slice.
        var guard = 0
        while (backfiller.runBackfill() && guard++ < 10) { /* keep going until no more work */ }

        assertEquals(TOTAL, distinctCachedUids().size, "backfill must cache every message")
        assertTrue(cached.size > WINDOW, "that is strictly more than the foreground window")
        assertEquals(true, progress["acct" to "INBOX"]?.complete)
        assertNoDeletes()
    }

    @Test
    fun `resumes from the persisted boundary after an interruption`() = runTest {
        appendMessages(TOTAL)
        seedForegroundWindow()

        // First run: only ONE page, then "process death" — keep just cached rows + persisted boundary.
        val partial = backfiller(AccountSettings("acct")).runBackfill(maxBatches = 1)
        assertTrue(partial, "one page can't finish 120 messages")
        assertTrue(cached.size in (WINDOW + 1) until TOTAL)
        val boundary = progress["acct" to "INBOX"]
        assertNotNull(boundary)
        assertFalse(boundary.complete)

        // Fresh backfiller instance (new process) resumes ONLY from what was persisted.
        val resumed = backfiller(AccountSettings("acct"))
        var guard = 0
        while (resumed.runBackfill() && guard++ < 10) { /* finish */ }

        assertEquals(TOTAL, distinctCachedUids().size, "resume completes the full history")
        assertEquals(TOTAL, cached.size, "no message is fetched twice")
        assertNoDeletes()
    }

    @Test
    fun `stops at the retention floor instead of paging the whole folder`() = runTest {
        appendMessages(TOTAL)
        seedForegroundWindow()

        // Keep only the newest 60: backfill pages until it has at least 60, then stops well short of 120.
        val backfiller = backfiller(AccountSettings("acct", retentionCount = 60))
        backfiller.runBackfill()
        val afterFirst = cached.size

        assertTrue(afterFirst >= 60, "must fetch at least up to the retention floor")
        assertTrue(afterFirst < TOTAL, "must NOT page the entire 120-message history")
        // Paused at the floor (NOT marked complete, so a later loosening could resume), and stable:
        // running again fetches nothing more.
        assertFalse(progress["acct" to "INBOX"]!!.complete)
        backfiller.runBackfill()
        assertEquals(afterFirst, cached.size, "at the floor, further runs must not fetch more")
        assertNoDeletes()
    }

    /** Builds a backfiller wired to GreenMail with the in-memory fakes and the given account settings. */
    private fun backfiller(accountSettings: AccountSettings): MailBackfiller {
        val accountDao = mockk<AccountDao>()
        coEvery { accountDao.getAll() } returns listOf(accountEntity)

        val messageDao = mockk<MessageDao>(relaxed = true)
        coEvery { messageDao.insertNew(any()) } answers {
            firstArg<List<MessageEntity>>().forEach { e -> if (cached.none { it.id == e.id }) cached += e }
        }
        coEvery { messageDao.syncedFolders("acct") } answers {
            cached.filter { it.inInbox }.map { it.folder }.distinct()
        }
        coEvery { messageDao.lowestSyncedUid("acct", any()) } answers {
            val folder = secondArg<String>()
            cached.filter { it.inInbox && it.folder == folder }.minOfOrNull { it.uid }
        }
        coEvery { messageDao.countSynced("acct", any()) } answers {
            val folder = secondArg<String>()
            cached.count { it.inInbox && it.folder == folder }
        }
        coEvery { messageDao.oldestSyncedTimestamp("acct", any()) } answers {
            val folder = secondArg<String>()
            cached.filter { it.inInbox && it.folder == folder }.minOfOrNull { it.timestampMillis }
        }

        val backfillProgressDao = mockk<BackfillProgressDao>(relaxed = true)
        coEvery { backfillProgressDao.get("acct", any()) } answers { progress["acct" to secondArg<String>()] }
        coEvery { backfillProgressDao.upsert(any()) } answers {
            val p = firstArg<BackfillProgressEntity>()
            progress[p.accountId to p.folder] = p
        }

        val connectionFactory = mockk<MailConnectionFactory>()
        coEvery { connectionFactory.imapParamsFor(any()) } returns params()

        val settingsRepository = mockk<SettingsRepository>()
        coEvery { settingsRepository.fetchPolicy() } returns FetchPolicy.ON_DEMAND
        every { settingsRepository.settings } returns flowOf(AppSettings())

        val accountSettingsRepository = mockk<AccountSettingsRepository>()
        coEvery { accountSettingsRepository.get("acct") } returns accountSettings

        return MailBackfiller(
            context = mockk<Context>(relaxed = true),
            accountDao = accountDao,
            messageDao = messageDao,
            backfillProgressDao = backfillProgressDao,
            imapClient = client,
            connectionFactory = connectionFactory,
            settingsRepository = settingsRepository,
            accountSettingsRepository = accountSettingsRepository,
            mailRepository = mockk<MailRepository>(relaxed = true),
            maintenanceGate = MailMaintenanceGate(),
        ).also { lastMessageDao = messageDao }
    }

    private var lastMessageDao: MessageDao? = null

    /** Seeds the in-memory cache with the newest [WINDOW] headers, mimicking a prior foreground sync. */
    private suspend fun seedForegroundWindow() {
        client.fetchRecent(params(), "INBOX", limit = WINDOW)
            .map { it.toEntity("acct", "INBOX") }
            .forEach { cached += it }
    }

    private fun distinctCachedUids(): Set<Long> = cached.map { it.uid }.toSet()

    private fun assertNoDeletes() {
        val dao = lastMessageDao ?: return
        coVerify(exactly = 0) { dao.deleteByIds(any()) }
        coVerify(exactly = 0) { dao.deleteSyncedNotIn(any(), any(), any()) }
        coVerify(exactly = 0) { dao.deleteSyncedInWindowNotIn(any(), any(), any(), any()) }
        coVerify(exactly = 0) { dao.deleteSyncedByAccountFolder(any(), any()) }
    }

    private fun appendMessages(count: Int) {
        val props = Properties().apply {
            put("mail.store.protocol", "imap")
            put("mail.imap.host", "127.0.0.1")
            put("mail.imap.port", greenMail.imap.port.toString())
        }
        val session = Session.getInstance(props)
        val store = session.getStore("imap")
        store.connect("127.0.0.1", greenMail.imap.port, "alice@example.org", "secret")
        try {
            val inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_WRITE)
            val messages = (1..count).map { i ->
                MimeMessage(session).apply {
                    setFrom(InternetAddress("sender$i@example.org"))
                    setRecipient(Message.RecipientType.TO, InternetAddress("alice@example.org"))
                    subject = "Message $i"
                    setText("Body of message $i")
                }
            }.toTypedArray()
            inbox.appendMessages(messages)
            inbox.close(false)
        } finally {
            store.close()
        }
    }

    private companion object {
        const val TOTAL = 120
        const val WINDOW = 50
    }
}
