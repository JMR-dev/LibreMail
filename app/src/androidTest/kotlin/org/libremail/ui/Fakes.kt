// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui

import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.libremail.data.sync.Syncer
import org.libremail.domain.model.Account
import org.libremail.domain.model.Attachment
import org.libremail.domain.model.Draft
import org.libremail.domain.model.Folder
import org.libremail.domain.model.ImapConnectionParams
import org.libremail.domain.model.InlineImage
import org.libremail.domain.model.Message
import org.libremail.domain.model.OutboxMessage
import org.libremail.domain.model.OutgoingMessage
import org.libremail.domain.model.ReplyMode
import org.libremail.domain.model.UnreadCount
import org.libremail.domain.repository.AccountRepository
import org.libremail.domain.repository.MailRepository
import java.io.File

/**
 * In-memory [AccountRepository] for Compose UI tests: serves a fixed account list, records the
 * account/password handed to [addImapAccount], and returns a configurable connection-test result.
 */
class FakeAccountRepository(
    accounts: List<Account> = emptyList(),
    private var result: Result<List<String>> = Result.success(listOf("INBOX")),
) : AccountRepository {

    private val accountsFlow = MutableStateFlow(accounts)

    var addedAccount: Account? = null
        private set
    var addedPassword: String? = null
        private set

    override fun observeAccounts(): Flow<List<Account>> = accountsFlow

    override suspend fun testConnection(params: ImapConnectionParams): Result<List<String>> = result

    override suspend fun addImapAccount(account: Account, password: String): Result<List<String>> {
        addedAccount = account
        addedPassword = password
        // Mirror the real repository: a successful add makes the account observable, so screens that
        // react to the account list (e.g. the mailbox after onboarding) see it appear.
        if (result.isSuccess) {
            accountsFlow.value = accountsFlow.value.filterNot { it.id == account.id } + account
        }
        return result
    }

    override suspend fun addOutlookAccount(
        email: String,
        accessToken: String,
        authStateJson: String,
    ): Result<List<String>> = result

    override suspend fun deleteAccount(id: String) {
        accountsFlow.value = accountsFlow.value.filterNot { it.id == id }
    }

    override suspend fun resetBackfillProgress(accountId: String?) = Unit
}

/**
 * In-memory [MailRepository] for Compose UI tests: records sent messages / saved drafts and returns
 * a configurable [sendResult]. Read paths emit empty so screens render their empty states.
 */
class FakeMailRepository(
    var sendResult: Result<Unit> = Result.success(Unit),
    private val messages: List<Message> = emptyList(),
    private val folders: List<Folder> = emptyList(),
    private val attachments: List<Attachment> = emptyList(),
    private val downloadedParts: Set<Int> = emptySet(),
    private val unreadCounts: List<UnreadCount> = emptyList(),
    // When set, every paged query returns this instead of a static page over [messages]. Lets a UI test
    // drive an explicit LoadState (e.g. refresh = Loading) through collectAsLazyPagingItems to exercise
    // the empty-state gate (issue #219).
    private val pagedOverride: Flow<PagingData<Message>>? = null,
) : MailRepository {

    val sentMessages = mutableListOf<OutgoingMessage>()
    val savedDrafts = mutableListOf<Draft>()
    val deletedDraftIds = mutableListOf<String>()
    val archivedIds = mutableListOf<List<String>>()
    val spammedIds = mutableListOf<List<String>>()
    val trashedIds = mutableListOf<List<String>>()
    val expungedIds = mutableListOf<List<String>>()
    val movedToFolder = mutableListOf<Pair<List<String>, String>>()
    val replyDrafts = mutableListOf<Pair<String, ReplyMode>>()

    override fun pagedUnifiedFolderMessages(folder: String): Flow<PagingData<Message>> =
        pagedOverride ?: flowOf(PagingData.from(messages.filter { it.folder == folder && it.inInbox }))

    override fun pagedFolderMessages(accountId: String, folder: String): Flow<PagingData<Message>> =
        pagedOverride ?: flowOf(
            PagingData.from(messages.filter { it.accountId == accountId && it.folder == folder && it.inInbox }),
        )

    override fun pagedUnifiedSearchMessages(folder: String, query: String): Flow<PagingData<Message>> =
        pagedOverride ?: flowOf(PagingData.from(messages.filter { it.folder == folder && it.matchesSearch(query) }))

    override fun pagedFolderSearchMessages(
        accountId: String,
        folder: String,
        query: String,
    ): Flow<PagingData<Message>> = pagedOverride ?: flowOf(
        PagingData.from(
            messages.filter { it.accountId == accountId && it.folder == folder && it.matchesSearch(query) },
        ),
    )

    override fun observeFolders(accountId: String): Flow<List<Folder>> = flowOf(
        folders.filter {
            it.accountId ==
                accountId
        },
    )

    override fun observeUnreadCounts(): Flow<List<UnreadCount>> = flowOf(unreadCounts)

    override suspend fun refreshFolders(accountId: String): Result<Unit> = Result.success(Unit)

    override suspend fun getMessage(id: String): Message? = messages.firstOrNull { it.id == id }

    override suspend fun openMessage(id: String): Result<Message> =
        messages.firstOrNull { it.id == id }?.let { Result.success(it) }
            ?: Result.failure(NoSuchElementException("no message $id"))

    override fun observeAttachments(messageId: String): Flow<List<Attachment>> = flowOf(
        attachments.filter {
            it.messageId ==
                messageId
        },
    )

    override suspend fun inlineImages(messageId: String): List<InlineImage> = emptyList()

    override suspend fun downloadAttachment(messageId: String, partIndex: Int): Result<File> =
        Result.failure(UnsupportedOperationException("not used in UI tests"))

    override suspend fun prefetchMessage(messageId: String): Result<Unit> = Result.success(Unit)

    override suspend fun downloadedAttachmentParts(messageId: String): Set<Int> = downloadedParts

    override suspend fun setStarred(id: String, starred: Boolean): Result<Unit> = Result.success(Unit)

    override suspend fun deleteMessage(id: String): Result<Unit> = Result.success(Unit)

    override suspend fun archive(ids: List<String>): Result<Unit> {
        archivedIds += ids
        return Result.success(Unit)
    }

    override suspend fun reportSpam(ids: List<String>): Result<Unit> {
        spammedIds += ids
        return Result.success(Unit)
    }

    override suspend fun trash(ids: List<String>): Result<Unit> {
        trashedIds += ids
        return Result.success(Unit)
    }

    override suspend fun expunge(ids: List<String>): Result<Unit> {
        expungedIds += ids
        return Result.success(Unit)
    }

    override suspend fun moveToFolder(ids: List<String>, destFolderFullName: String): Result<Unit> {
        movedToFolder += ids to destFolderFullName
        return Result.success(Unit)
    }

    override suspend fun buildReplyDraft(messageId: String, mode: ReplyMode): Result<String> {
        replyDrafts += messageId to mode
        return Result.success("draft-$messageId")
    }

    override suspend fun sendMessage(outgoing: OutgoingMessage): Result<Unit> {
        sentMessages += outgoing
        return sendResult
    }

    override fun observeDrafts(): Flow<List<Draft>> = flowOf(emptyList())

    override suspend fun getDraft(id: String): Draft? = null

    override suspend fun saveDraft(draft: Draft) {
        savedDrafts += draft
    }

    override suspend fun deleteDraft(id: String) {
        deletedDraftIds += id
    }

    override fun observeOutbox(): Flow<List<OutboxMessage>> = flowOf(emptyList())

    override suspend fun cancelOutboxMessage(id: String) {}

    override suspend fun retryOutbox() {}

    override suspend fun searchServer(query: String, accountId: String?, folder: String) {}

    override suspend fun clearSearchResults() {}
}

/** No-op [Syncer] for UI tests: the screen renders cached data, so sync calls do nothing. */
class FakeMailSyncer : Syncer {
    override suspend fun syncAll(): Result<Int> = Result.success(0)
    override suspend fun syncAccount(accountId: String): Result<Int> = Result.success(0)
    override suspend fun syncFolder(accountId: String, folder: String): Result<Int> = Result.success(0)
}

/** Mirrors the paged-search DAO queries' columns so the fake's search pagers filter like production. */
private fun Message.matchesSearch(query: String): Boolean = sender.contains(query, ignoreCase = true) ||
    senderEmail.contains(query, ignoreCase = true) ||
    subject.contains(query, ignoreCase = true) ||
    snippet.contains(query, ignoreCase = true)
