// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.repository

import app.cash.turbine.test
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.libremail.data.local.dao.AccountDao
import org.libremail.data.local.dao.BackfillProgressDao
import org.libremail.data.local.dao.FolderDao
import org.libremail.data.local.dao.MessageDao
import org.libremail.data.local.entity.AccountEntity
import org.libremail.data.local.entity.ServerConfigEmbedded
import org.libremail.data.security.CredentialStore
import org.libremail.data.settings.AccountSettingsRepository
import org.libremail.data.sync.SyncScheduler
import org.libremail.domain.model.Account
import org.libremail.domain.model.AuthType
import org.libremail.domain.model.ImapConnectionParams
import org.libremail.domain.model.MailSecurity
import org.libremail.domain.model.ServerConfig
import org.libremail.mail.FetchedFolder
import org.libremail.mail.ImapClient
import org.libremail.notifications.MailNotifier
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers [AccountRepositoryImpl]'s add/remove/observe surface: the happy paths persist the account,
 * its credential, its notification channel and kick sync + backfill; the failure paths (a rejected
 * initial folder LIST) must persist nothing. Every collaborator is mocked, so the assertions pin the
 * repository's own orchestration, not the DAOs or the IMAP client.
 */
class AccountRepositoryImplTest {

    private val accountDao = mockk<AccountDao>()
    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val folderDao = mockk<FolderDao>(relaxed = true)
    private val backfillProgressDao = mockk<BackfillProgressDao>(relaxed = true)
    private val credentialStore = mockk<CredentialStore>(relaxed = true)
    private val imapClient = mockk<ImapClient>()
    private val syncScheduler = mockk<SyncScheduler>(relaxed = true)
    private val accountSettingsRepository = mockk<AccountSettingsRepository>(relaxed = true)
    private val mailNotifier = mockk<MailNotifier>(relaxed = true)

    private val repository = AccountRepositoryImpl(
        accountDao = accountDao,
        messageDao = messageDao,
        folderDao = folderDao,
        backfillProgressDao = backfillProgressDao,
        credentialStore = credentialStore,
        imapClient = imapClient,
        syncScheduler = syncScheduler,
        accountSettingsRepository = accountSettingsRepository,
        mailNotifier = mailNotifier,
    )

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
        coEvery { accountDao.upsert(any()) } just Runs

        val result = repository.addImapAccount(account, "app-password")

        assertEquals(listOf("INBOX"), result.getOrThrow())
        // A password account authenticates the LIST with the app password, not XOAUTH2.
        assertFalse(used.captured.useXoauth2)
        assertEquals("app-password", used.captured.secret)
        assertEquals("ada@example.org", used.captured.username)
        coVerify { accountDao.upsert(any()) }
        coVerify { accountSettingsRepository.ensureDefaults(account.id) }
        coVerify { credentialStore.saveSecret(account.id, "app-password") }
        verify { mailNotifier.ensureAccountChannel(account) }
        verify { syncScheduler.syncNow() }
        verify { syncScheduler.backfillNow() }
    }

    @Test
    fun `addImapAccount persists nothing when the initial folder list fails`() = runTest {
        coEvery { imapClient.listFolders(any()) } throws RuntimeException("bad credentials")

        val result = repository.addImapAccount(account(), "wrong")

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { accountDao.upsert(any()) }
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
        coEvery { accountDao.upsert(capture(saved)) } just Runs

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
        coVerify(exactly = 0) { accountDao.upsert(any()) }
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

    private val params = ImapConnectionParams(
        host = "imap.example.org",
        port = 993,
        security = MailSecurity.SSL_TLS,
        username = "ada@example.org",
        secret = "secret",
        useXoauth2 = false,
    )
}
