// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui

import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.libremail.domain.model.Account
import org.libremail.domain.model.Attachment
import org.libremail.domain.model.Draft
import org.libremail.domain.model.ImapConnectionParams
import org.libremail.domain.model.Message
import org.libremail.domain.model.OutboxMessage
import org.libremail.domain.model.OutgoingMessage
import org.libremail.domain.repository.AccountRepository
import org.libremail.domain.repository.MailRepository

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
}

/**
 * In-memory [MailRepository] for Compose UI tests: records sent messages / saved drafts and returns
 * a configurable [sendResult]. Read paths emit empty so screens render their empty states.
 */
class FakeMailRepository(
    var sendResult: Result<Unit> = Result.success(Unit),
) : MailRepository {

    val sentMessages = mutableListOf<OutgoingMessage>()
    val savedDrafts = mutableListOf<Draft>()
    val deletedDraftIds = mutableListOf<String>()

    override fun observeMessages(): Flow<List<Message>> = flowOf(emptyList())

    override suspend fun getMessage(id: String): Message? = null

    override suspend fun openMessage(id: String): Result<Message> =
        Result.failure(UnsupportedOperationException("not used in UI tests"))

    override fun observeAttachments(messageId: String): Flow<List<Attachment>> = flowOf(emptyList())

    override suspend fun downloadAttachment(messageId: String, partIndex: Int): Result<File> =
        Result.failure(UnsupportedOperationException("not used in UI tests"))

    override suspend fun setStarred(id: String, starred: Boolean): Result<Unit> = Result.success(Unit)

    override suspend fun deleteMessage(id: String): Result<Unit> = Result.success(Unit)

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

    override suspend fun searchServer(query: String) {}

    override suspend fun clearSearchResults() {}
}
