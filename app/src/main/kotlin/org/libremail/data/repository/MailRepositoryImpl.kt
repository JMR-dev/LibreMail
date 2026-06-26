// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.libremail.data.local.dao.MessageDao
import org.libremail.data.local.toDomain
import org.libremail.domain.model.Message
import org.libremail.domain.repository.MailRepository

@Singleton
class MailRepositoryImpl @Inject constructor(
    private val messageDao: MessageDao,
) : MailRepository {

    override fun observeMessages(): Flow<List<Message>> =
        messageDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun getMessage(id: String): Message? =
        messageDao.getById(id)?.toDomain()
}
