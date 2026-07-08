// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.data.attachment.AttachmentUriGrants
import org.libremail.data.local.AccountDatabase
import org.libremail.data.local.dao.BackfillProgressDao
import org.libremail.data.local.dao.DraftDao
import org.libremail.data.local.dao.FolderDao
import org.libremail.data.local.dao.MessageDao
import org.libremail.data.security.CredentialStore
import org.libremail.data.security.KeystoreCrypto
import org.libremail.data.settings.AccountSettingsRepository
import org.libremail.data.sync.SyncScheduler
import org.libremail.domain.model.Account
import org.libremail.domain.model.AuthType
import org.libremail.domain.model.MailSecurity
import org.libremail.domain.model.ServerConfig
import org.libremail.mail.FetchedFolder
import org.libremail.mail.ImapClient
import org.libremail.notifications.MailNotifier

/**
 * On-device proof of the #403 fix against real SQLite and the real Keystore-backed [CredentialStore]:
 * when [AccountRepositoryImpl] adds an account, its credential must be resolvable the instant the new
 * account row becomes observable — the exact moment the push watchers (LibreMailApplication's collector
 * and [org.libremail.push.IdleService.reconcileWatchers], both keyed on the *accounts* table) react.
 *
 * The JVM `AccountRepositoryImplTest` pins the call ORDER with `coVerifyOrder`; this drives the same
 * production code against a real in-memory [AccountDatabase] (real `accountDao` + real `credentialDao`
 * via a real [CredentialStore]/[KeystoreCrypto]) so the transaction-commit ordering — not just the call
 * ordering — is exercised. A background collector reads the credential the moment the account first
 * appears, reproducing the reactive watcher; with the secret committed before the row it always
 * resolves. Non-DB collaborators (IMAP, scheduler, notifier, settings) are mocked the same way
 * `WorkerCacheLockDeferralInstrumentedTest` fakes its non-framework collaborators — never a framework
 * `Context`, which is the real application context.
 */
@RunWith(AndroidJUnit4::class)
class AccountAddCredentialOrderingInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var db: AccountDatabase
    private lateinit var credentialStore: CredentialStore
    private lateinit var imapClient: ImapClient
    private lateinit var repository: AccountRepositoryImpl

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AccountDatabase::class.java).build()
        credentialStore = CredentialStore(KeystoreCrypto(), db.credentialDao())
        imapClient = mockk()
        coEvery { imapClient.listFolders(any()) } returns listOf(
            FetchedFolder("INBOX", "INBOX", emptyList(), selectable = true),
        )
        repository = AccountRepositoryImpl(
            context = context,
            accountDao = db.accountDao(),
            messageDao = mockk<MessageDao>(relaxed = true),
            folderDao = mockk<FolderDao>(relaxed = true),
            backfillProgressDao = mockk<BackfillProgressDao>(relaxed = true),
            draftDao = mockk<DraftDao>(relaxed = true),
            credentialStore = credentialStore,
            imapClient = imapClient,
            syncScheduler = mockk<SyncScheduler>(relaxed = true),
            accountSettingsRepository = mockk<AccountSettingsRepository>(relaxed = true),
            mailNotifier = mockk<MailNotifier>(relaxed = true),
            attachmentUriGrants = mockk<AttachmentUriGrants>(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        db.close()
        unmockkAll()
    }

    @Test
    fun newAccountRowIsObservableOnlyAfterItsCredentialIsResolvable() = runBlocking {
        val account = imapAccount()

        // A reactive watcher: read the stored secret the moment the new account row first appears in the
        // observed accounts list — exactly what IdleService.reconcileWatchers does before opening IDLE.
        val credentialAtFirstSight = CompletableDeferred<String?>()
        val observer = launch(Dispatchers.IO) {
            repository.observeAccounts().collect { accounts ->
                if (accounts.any { it.id == account.id } && !credentialAtFirstSight.isCompleted) {
                    credentialAtFirstSight.complete(credentialStore.loadSecret(account.id))
                }
            }
        }

        repository.addImapAccount(account, PASSWORD).getOrThrow()

        assertEquals(
            "the credential must be resolvable the instant the account row becomes observable (#403)",
            PASSWORD,
            withTimeout(TIMEOUT_MS) { credentialAtFirstSight.await() },
        )
        observer.cancel()
    }

    @Test
    fun addImapAccountLeavesTheCredentialResolvableForTheStoredAccount() = runBlocking {
        val account = imapAccount()

        repository.addImapAccount(account, PASSWORD).getOrThrow()

        // Durability backstop: the account is persisted AND its secret round-trips through the real
        // Keystore-sealed store, so any later push (re)start resolves it rather than hitting a hard miss.
        assertEquals(PASSWORD, credentialStore.loadSecret(account.id))
        assertEquals(account.id, db.accountDao().getById(account.id)?.id)
    }

    private fun imapAccount(id: String = "imap:ada@example.org") = Account(
        id = id,
        email = "ada@example.org",
        displayName = "Ada",
        authType = AuthType.PASSWORD_IMAP,
        imap = ServerConfig("imap.example.org", 993, MailSecurity.SSL_TLS),
        smtp = ServerConfig("smtp.example.org", 587, MailSecurity.STARTTLS),
    )

    private companion object {
        const val PASSWORD = "app-password"
        const val TIMEOUT_MS = 5_000L
    }
}
