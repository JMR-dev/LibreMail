// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.libremail.data.attachment.AttachmentUriGrants
import org.libremail.data.attachmentCacheDir
import org.libremail.data.local.dao.AccountDao
import org.libremail.data.local.dao.BackfillProgressDao
import org.libremail.data.local.dao.DraftDao
import org.libremail.data.local.dao.FolderDao
import org.libremail.data.local.dao.MessageDao
import org.libremail.data.local.toDomain
import org.libremail.data.local.toEntity
import org.libremail.data.local.toImapParams
import org.libremail.data.local.toOutgoingAttachments
import org.libremail.data.security.CredentialStore
import org.libremail.data.settings.AccountSettingsRepository
import org.libremail.data.sync.SyncScheduler
import org.libremail.domain.model.Account
import org.libremail.domain.model.ImapConnectionParams
import org.libremail.domain.repository.AccountRepository
import org.libremail.mail.ImapClient
import org.libremail.notifications.MailNotifier
import org.libremail.reporting.AppLog
import org.libremail.reporting.accountLogRef
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountDao: AccountDao,
    private val messageDao: MessageDao,
    private val folderDao: FolderDao,
    private val backfillProgressDao: BackfillProgressDao,
    private val draftDao: DraftDao,
    private val credentialStore: CredentialStore,
    private val imapClient: ImapClient,
    private val syncScheduler: SyncScheduler,
    private val accountSettingsRepository: AccountSettingsRepository,
    private val mailNotifier: MailNotifier,
    private val attachmentUriGrants: AttachmentUriGrants,
) : AccountRepository {

    override fun observeAccounts(): Flow<List<Account>> = accountDao.observeAll().map { rows ->
        rows.map { it.toDomain() }
    }

    override suspend fun testConnection(params: ImapConnectionParams): Result<List<String>> = runCatching {
        imapClient.listFolders(params).map { it.fullName }
    }

    override suspend fun addImapAccount(account: Account, password: String): Result<List<String>> = runCatching {
        val folders = imapClient.listFolders(account.toImapParams(secret = password, useXoauth2 = false))
        // Persist the credential BEFORE inserting the account row (#403). Both LibreMailApplication's
        // push collector and IdleService.reconcileWatchers react to the *accounts* table; committing the
        // secret first guarantees any watcher that observes the new row can already resolve it, instead
        // of firing a transient "No stored credentials" IDLE miss on every account add. The credentials
        // table has no foreign key to accounts, so it can be written first; account_settings does (FK),
        // so ensureDefaults must still follow the account row.
        credentialStore.saveSecret(account.id, password)
        accountDao.insertAtEnd(account.toEntity())
        accountSettingsRepository.ensureDefaults(account.id)
        mailNotifier.ensureAccountChannel(account)
        AppLog.i(TAG, "IMAP account added ${accountLogRef(account.id)}; credential persisted before account row")
        syncScheduler.syncNow()
        syncScheduler.backfillNow() // start caching this account's full history in the background (#12)
        folders.map { it.fullName }
    }

    override suspend fun addOutlookAccount(
        email: String,
        accessToken: String,
        authStateJson: String,
    ): Result<List<String>> = runCatching {
        val account = Account.outlook(email)
        val folders = imapClient.listFolders(account.toImapParams(secret = accessToken, useXoauth2 = true))
        // Persist the durable AuthState BEFORE the account row (#403) — same ordering rationale as
        // addImapAccount: the push watchers observe the accounts table, so the secret must be committed
        // first for the newly-observed account to resolve. account_settings' FK still needs the row, so
        // ensureDefaults follows the insert.
        credentialStore.saveSecret(account.id, authStateJson)
        accountDao.insertAtEnd(account.toEntity())
        accountSettingsRepository.ensureDefaults(account.id)
        mailNotifier.ensureAccountChannel(account)
        AppLog.i(TAG, "Outlook account added ${accountLogRef(account.id)}; credential persisted before account row")
        syncScheduler.syncNow()
        syncScheduler.backfillNow() // start caching this account's full history in the background (#12)
        folders.map { it.fullName }
    }

    override suspend fun reorderAccounts(orderedIds: List<String>) = accountDao.reorder(orderedIds)

    override suspend fun deleteAccount(id: String) {
        // Enumerate the on-disk artifacts to clean up WHILE the rows that name them still exist: once
        // the message + draft rows are gone, nothing can recover those message ids / draft URIs again,
        // so the files/grants would leak forever (issue #299).
        val messageIds = messageDao.getIdsForAccount(id)
        val draftUris = draftDao.getByAccount(id)
            .flatMap { it.attachments.toOutgoingAttachments() }
            .map { it.uri }

        accountDao.deleteById(id)
        credentialStore.delete(id)
        mailNotifier.deleteAccountChannel(id)
        // Remove the account's cached mail (attachment rows cascade via the foreign key), folders,
        // backfill progress, and drafts. The account_settings + signatures rows are removed
        // automatically by their cascading FK when the account row above is deleted.
        messageDao.deleteByAccount(id)
        folderDao.deleteForAccount(id)
        backfillProgressDao.deleteForAccount(id)
        draftDao.deleteByAccount(id)

        // Now the rows are gone: delete each message's on-disk attachment cache (keyed by message id,
        // the same path MailRepositoryImpl writes), and release any persistable draft-URI grant no
        // remaining draft/outbox row still needs (issue #299).
        messageIds.forEach { messageId ->
            runCatching { attachmentCacheDir(context.cacheDir, messageId).deleteRecursively() }
        }
        attachmentUriGrants.releaseUnreferenced(draftUris)
    }

    override suspend fun resetBackfillProgress(accountId: String?) {
        if (accountId != null) backfillProgressDao.deleteForAccount(accountId) else backfillProgressDao.deleteAll()
        syncScheduler.backfillNow()
    }

    private companion object {
        const val TAG = "AccountRepository"
    }
}
