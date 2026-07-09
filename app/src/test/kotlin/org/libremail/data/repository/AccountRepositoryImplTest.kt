// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.repository

import android.content.Context
import android.util.Log
import app.cash.turbine.test
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.data.attachment.AttachmentUriGrants
import org.libremail.data.attachmentCacheDir
import org.libremail.data.local.dao.AccountDao
import org.libremail.data.local.dao.BackfillProgressDao
import org.libremail.data.local.dao.DraftDao
import org.libremail.data.local.dao.FolderDao
import org.libremail.data.local.dao.MessageDao
import org.libremail.data.local.entity.AccountEntity
import org.libremail.data.local.entity.DraftEntity
import org.libremail.data.local.entity.ServerConfigEmbedded
import org.libremail.data.security.CredentialStore
import org.libremail.data.settings.AccountSettingsRepository
import org.libremail.data.sync.SyncScheduler
import org.libremail.domain.model.Account
import org.libremail.domain.model.AuthType
import org.libremail.domain.model.ImapConnectionParams
import org.libremail.domain.model.MailSecurity
import org.libremail.domain.model.ServerConfig
import org.libremail.mail.AuthThrottleGate
import org.libremail.mail.FetchedFolder
import org.libremail.mail.ImapClient
import org.libremail.notifications.MailNotifier
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers [AccountRepositoryImpl]'s add/remove/observe surface: the happy paths persist the account,
 * its credential, its notification channel and kick sync + backfill; the failure paths (a rejected
 * initial folder LIST) must persist nothing. Every collaborator is mocked, so the assertions pin the
 * repository's own orchestration, not the DAOs or the IMAP client.
 */
class AccountRepositoryImplTest {

    private val context = mockk<Context>(relaxed = true)
    private val accountDao = mockk<AccountDao>()
    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val folderDao = mockk<FolderDao>(relaxed = true)
    private val backfillProgressDao = mockk<BackfillProgressDao>(relaxed = true)
    private val draftDao = mockk<DraftDao>(relaxed = true)
    private val credentialStore = mockk<CredentialStore>(relaxed = true)
    private val imapClient = mockk<ImapClient>()
    private val authGate = mockk<AuthThrottleGate>(relaxed = true)
    private val syncScheduler = mockk<SyncScheduler>(relaxed = true)
    private val accountSettingsRepository = mockk<AccountSettingsRepository>(relaxed = true)
    private val mailNotifier = mockk<MailNotifier>(relaxed = true)
    private val attachmentUriGrants = mockk<AttachmentUriGrants>(relaxed = true)

    private val repository = AccountRepositoryImpl(
        context = context,
        accountDao = accountDao,
        messageDao = messageDao,
        folderDao = folderDao,
        backfillProgressDao = backfillProgressDao,
        draftDao = draftDao,
        credentialStore = credentialStore,
        imapClient = imapClient,
        authGate = authGate,
        syncScheduler = syncScheduler,
        accountSettingsRepository = accountSettingsRepository,
        mailNotifier = mailNotifier,
        attachmentUriGrants = attachmentUriGrants,
    )

    // addImapAccount/addOutlookAccount now breadcrumb via AppLog (#403); android.util.Log is a no-op
    // stub under plain JVM tests, so mock it class-wide so no test crashes on the unmocked method.
    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `observeAccounts maps the stored account rows to domain models`() = runTest {
        every { accountDao.observeAll() } returns flowOf(listOf(accountEntity()))

        repository.observeAccounts().test {
            val accounts = awaitItem()
            assertEquals(1, accounts.size)
            assertEquals("acct", accounts.first().id)
            assertEquals("ada@example.org", accounts.first().email)
            assertEquals(AuthType.PASSWORD_IMAP, accounts.first().authType)
            assertEquals(MailSecurity.SSL_TLS, accounts.first().imap.security)
            awaitComplete()
        }
    }

    @Test
    fun `testConnection returns the server's folder names on success`() = runTest {
        coEvery { imapClient.listFolders(params) } returns listOf(
            FetchedFolder("INBOX", "INBOX", emptyList(), selectable = true),
            FetchedFolder("[Gmail]/Sent Mail", "Sent Mail", emptyList(), selectable = true),
        )

        val result = repository.testConnection(params)

        assertEquals(listOf("INBOX", "[Gmail]/Sent Mail"), result.getOrThrow())
    }

    @Test
    fun `testConnection wraps a connection failure in a failed result`() = runTest {
        coEvery { imapClient.listFolders(params) } throws RuntimeException("no route to host")

        assertTrue(repository.testConnection(params).isFailure)
    }

    @Test
    fun `addImapAccount lists folders, persists the account, and starts syncing`() = runTest {
        val account = account()
        val used = slot<ImapConnectionParams>()
        coEvery { imapClient.listFolders(capture(used)) } returns listOf(
            FetchedFolder("INBOX", "INBOX", emptyList(), selectable = true),
        )
        coEvery { accountDao.insertAtEnd(any()) } just Runs

        val result = repository.addImapAccount(account, "app-password")

        assertEquals(listOf("INBOX"), result.getOrThrow())
        // A password account authenticates the LIST with the app password, not XOAUTH2.
        assertFalse(used.captured.useXoauth2)
        assertEquals("app-password", used.captured.secret)
        assertEquals("ada@example.org", used.captured.username)
        coVerify { accountDao.insertAtEnd(any()) }
        coVerify { accountSettingsRepository.ensureDefaults(account.id) }
        coVerify { credentialStore.saveSecret(account.id, "app-password") }
        verify { mailNotifier.ensureAccountChannel(account) }
        verify { syncScheduler.syncNow() }
        verify { syncScheduler.backfillNow() }
    }

    @Test
    fun `addImapAccount persists the credential before the account row (issue 403)`() = runTest {
        val account = account()
        coEvery { imapClient.listFolders(any()) } returns listOf(
            FetchedFolder("INBOX", "INBOX", emptyList(), selectable = true),
        )
        coEvery { accountDao.insertAtEnd(any()) } just Runs

        repository.addImapAccount(account, "app-password").getOrThrow()

        // The push collector (LibreMailApplication) and IdleService.reconcileWatchers both react to the
        // accounts table; the secret must be committed FIRST so a watcher observing the new row can
        // resolve it, rather than logging a transient "No stored credentials" IDLE miss on every add.
        coVerifyOrder {
            credentialStore.saveSecret(account.id, "app-password")
            accountDao.insertAtEnd(any())
        }
    }

    @Test
    fun `addImapAccount resets a latched auth circuit before the test and clears the account error`() = runTest {
        val account = account()
        val entity = slot<AccountEntity>()
        coEvery { imapClient.listFolders(any()) } returns listOf(
            FetchedFolder("INBOX", "INBOX", emptyList(), selectable = true),
        )
        coEvery { accountDao.insertAtEnd(capture(entity)) } just Runs

        repository.addImapAccount(account, "app-password").getOrThrow()

        // #362: the in-memory latch is dropped BEFORE the connection test, so a fresh credential logs in
        // cleanly instead of being refused by a still-latched gate.
        coVerifyOrder {
            authGate.onAccountReadded(any())
            imapClient.listFolders(any())
        }
        // ...and the (re)written account row carries a null authError, clearing any persisted error state.
        assertNull(entity.captured.authError, "a re-add clears the persisted account error")
    }

    @Test
    fun `addOutlookAccount persists the credential before the account row (issue 403)`() = runTest {
        coEvery { imapClient.listFolders(any()) } returns listOf(
            FetchedFolder("INBOX", "INBOX", emptyList(), selectable = true),
        )
        coEvery { accountDao.insertAtEnd(any()) } just Runs

        repository.addOutlookAccount("me@outlook.com", "access-token", "{authstate}").getOrThrow()

        coVerifyOrder {
            credentialStore.saveSecret("outlook:me@outlook.com", "{authstate}")
            accountDao.insertAtEnd(any())
        }
    }

    @Test
    fun `addImapAccount persists nothing when the initial folder list fails`() = runTest {
        coEvery { imapClient.listFolders(any()) } throws RuntimeException("bad credentials")

        val result = repository.addImapAccount(account(), "wrong")

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { accountDao.insertAtEnd(any()) }
        coVerify(exactly = 0) { credentialStore.saveSecret(any(), any()) }
        verify(exactly = 0) { syncScheduler.syncNow() }
    }

    @Test
    fun `addOutlookAccount builds an Outlook account and stores its durable auth state via XOAUTH2`() = runTest {
        val used = slot<ImapConnectionParams>()
        coEvery { imapClient.listFolders(capture(used)) } returns listOf(
            FetchedFolder("INBOX", "INBOX", emptyList(), selectable = true),
        )
        val saved = slot<AccountEntity>()
        coEvery { accountDao.insertAtEnd(capture(saved)) } just Runs

        val result = repository.addOutlookAccount("me@outlook.com", "access-token", "{authstate}")

        assertEquals(listOf("INBOX"), result.getOrThrow())
        // The short-lived access token authenticates the initial LIST over XOAUTH2...
        assertTrue(used.captured.useXoauth2)
        assertEquals("access-token", used.captured.secret)
        // ...but the persisted secret is the durable AuthState JSON the refresh flow later reads.
        coVerify { credentialStore.saveSecret("outlook:me@outlook.com", "{authstate}") }
        assertEquals("outlook:me@outlook.com", saved.captured.id)
        assertEquals(AuthType.OAUTH_OUTLOOK.name, saved.captured.authType)
        verify { syncScheduler.syncNow() }
        verify { syncScheduler.backfillNow() }
    }

    @Test
    fun `addOutlookAccount fails and persists nothing when the token is rejected`() = runTest {
        coEvery { imapClient.listFolders(any()) } throws RuntimeException("401 unauthorized")

        val result = repository.addOutlookAccount("me@outlook.com", "expired", "{authstate}")

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { accountDao.insertAtEnd(any()) }
        coVerify(exactly = 0) { credentialStore.saveSecret(any(), any()) }
    }

    @Test
    fun `deleteAccount removes the account, its credential, channel, and all cached data`() = runTest {
        coEvery { accountDao.deleteById("acct") } just Runs

        repository.deleteAccount("acct")

        coVerify { accountDao.deleteById("acct") }
        coVerify { credentialStore.delete("acct") }
        verify { mailNotifier.deleteAccountChannel("acct") }
        coVerify { messageDao.deleteByAccount("acct") }
        coVerify { folderDao.deleteForAccount("acct") }
        coVerify { backfillProgressDao.deleteForAccount("acct") }
        coVerify { draftDao.deleteByAccount("acct") }
    }

    @Test
    fun `deleteAccount purges on-disk attachment caches and releases draft URI grants`() = runTest {
        // A real temp cacheDir with one message's attachment cache dir populated on disk.
        val cacheDir = File(System.getProperty("java.io.tmpdir"), "libremail-delete-test-${System.nanoTime()}")
        every { context.cacheDir } returns cacheDir
        val messageId = "acct:INBOX:1"
        val attachmentDir = attachmentCacheDir(cacheDir, messageId).apply { mkdirs() }
        File(attachmentDir, "0/report.pdf").apply { parentFile?.mkdirs() }.writeText("bytes")

        // The account owns that message and a draft carrying a picked attachment URI.
        coEvery { accountDao.deleteById("acct") } just Runs
        coEvery { messageDao.getIdsForAccount("acct") } returns listOf(messageId)
        coEvery { draftDao.getByAccount("acct") } returns listOf(
            draftEntity(id = "d1", attachments = """[{"uri":"content://pick/1","name":"report.pdf"}]"""),
        )
        val released = slot<Collection<String>>()
        coEvery { attachmentUriGrants.releaseUnreferenced(capture(released)) } just Runs

        repository.deleteAccount("acct")

        // The message ids must be read BEFORE the rows are deleted, and the on-disk cache purged.
        coVerify { messageDao.getIdsForAccount("acct") }
        assertFalse(attachmentDir.exists(), "attachment cache dir must be deleted with the account")
        // The deleted draft's picked-URI grant is released.
        coVerify { draftDao.deleteByAccount("acct") }
        assertEquals(listOf("content://pick/1"), released.captured.toList())

        cacheDir.deleteRecursively()
    }

    @Test
    fun `reorderAccounts forwards the new order to the dao (issue 240)`() = runTest {
        coEvery { accountDao.reorder(any()) } just Runs

        repository.reorderAccounts(listOf("c", "a", "b"))

        coVerify { accountDao.reorder(listOf("c", "a", "b")) }
    }

    @Test
    fun `resetBackfillProgress for one account clears only that account and re-kicks backfill`() = runTest {
        repository.resetBackfillProgress("acct")

        coVerify { backfillProgressDao.deleteForAccount("acct") }
        coVerify(exactly = 0) { backfillProgressDao.deleteAll() }
        verify { syncScheduler.backfillNow() }
    }

    @Test
    fun `resetBackfillProgress with no account clears all progress and re-kicks backfill`() = runTest {
        repository.resetBackfillProgress(null)

        coVerify { backfillProgressDao.deleteAll() }
        coVerify(exactly = 0) { backfillProgressDao.deleteForAccount(any()) }
        verify { syncScheduler.backfillNow() }
    }

    private fun account(id: String = "imap:ada@example.org") = Account(
        id = id,
        email = "ada@example.org",
        displayName = "Ada",
        authType = AuthType.PASSWORD_IMAP,
        imap = ServerConfig("imap.example.org", 993, MailSecurity.SSL_TLS),
        smtp = ServerConfig("smtp.example.org", 587, MailSecurity.STARTTLS),
    )

    private fun accountEntity(id: String = "acct", email: String = "ada@example.org") = AccountEntity(
        id = id,
        email = email,
        displayName = "Ada",
        authType = "PASSWORD_IMAP",
        imap = ServerConfigEmbedded("imap.example.org", 993, "SSL_TLS"),
        smtp = ServerConfigEmbedded("smtp.example.org", 465, "SSL_TLS"),
    )

    private fun draftEntity(id: String, attachments: String) = DraftEntity(
        id = id,
        accountId = "acct",
        toAddresses = "",
        ccAddresses = "",
        subject = "",
        body = "",
        updatedAt = 0L,
        attachments = attachments,
    )

    private val params = ImapConnectionParams(
        host = "imap.example.org",
        port = 993,
        security = MailSecurity.SSL_TLS,
        username = "ada@example.org",
        secret = "secret",
        useXoauth2 = false,
    )
}
