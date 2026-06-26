// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.repository

import app.cash.turbine.test
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.libremail.data.local.dao.MessageDao
import org.libremail.data.local.entity.MessageEntity
import org.libremail.data.sample.SampleData

class MailRepositoryImplTest {

    private val messageDao = mockk<MessageDao>()

    @Test
    fun `observeMessages emits sample data when cache is empty`() = runTest {
        every { messageDao.observeAll() } returns flowOf(emptyList())
        val repository = MailRepositoryImpl(messageDao)

        repository.observeMessages().test {
            assertEquals(SampleData.messages, awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `observeMessages maps cached entities when present`() = runTest {
        val entity = MessageEntity(
            id = "1",
            accountId = "a",
            sender = "Ada",
            senderEmail = "ada@example.org",
            subject = "Hi",
            snippet = "snippet",
            body = "body",
            timestampMillis = 1_000L,
            isRead = true,
            isStarred = false,
        )
        every { messageDao.observeAll() } returns flowOf(listOf(entity))
        val repository = MailRepositoryImpl(messageDao)

        repository.observeMessages().test {
            val items = awaitItem()
            assertEquals(1, items.size)
            assertEquals("Ada", items.first().sender)
            assertTrue(items.first().isRead)
            awaitComplete()
        }
    }
}
