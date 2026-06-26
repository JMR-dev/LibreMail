// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.libremail.data.local.dao.MessageDao
import org.libremail.data.local.entity.MessageEntity
import org.libremail.data.sample.SampleData
import org.libremail.domain.model.Message
import org.libremail.domain.repository.MailRepository

@Singleton
class MailRepositoryImpl @Inject constructor(
    private val messageDao: MessageDao,
) : MailRepository {

    // Room is the single source of truth. Until sync lands the cache is empty, so we fall
    // back to bundled sample data to keep the UI populated.
    override fun observeMessages(): Flow<List<Message>> =
        messageDao.observeAll().map { cached ->
            if (cached.isEmpty()) SampleData.messages else cached.map { it.toDomain() }
        }

    override suspend fun getMessage(id: String): Message? =
        messageDao.getById(id)?.toDomain() ?: SampleData.byId(id)
}

private fun MessageEntity.toDomain(): Message = Message(
    id = id,
    accountId = accountId,
    sender = sender,
    senderEmail = senderEmail,
    subject = subject,
    snippet = snippet,
    body = body,
    timestampMillis = timestampMillis,
    isRead = isRead,
    isStarred = isStarred,
)
