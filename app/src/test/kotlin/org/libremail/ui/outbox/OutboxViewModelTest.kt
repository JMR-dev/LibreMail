// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.outbox

import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.domain.model.OutboxMessage
import org.libremail.domain.repository.MailRepository
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class OutboxViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun message(id: String) = OutboxMessage(
        id = id,
        to = "to@example.org",
        subject = "Subject",
        body = "Body",
        createdAt = 1L,
        lastError = null,
    )

    @Test
    fun `messages mirrors the repository's outbox stream once collected`() = runTest(dispatcher) {
        val repo = mockk<MailRepository>(relaxed = true)
        every { repo.observeOutbox() } returns MutableStateFlow(listOf(message("m1"), message("m2")))
        val vm = OutboxViewModel(repo)

        backgroundScope.launch { vm.messages.collect {} }
        runCurrent()

        assertEquals(listOf("m1", "m2"), vm.messages.value.map { it.id })
    }

    @Test
    fun `messages starts empty before the stream emits`() = runTest(dispatcher) {
        val repo = mockk<MailRepository>(relaxed = true)
        every { repo.observeOutbox() } returns flowOf(emptyList())
        val vm = OutboxViewModel(repo)

        assertEquals(emptyList(), vm.messages.value)
    }

    @Test
    fun `cancel delegates to the repository`() = runTest(dispatcher) {
        val repo = mockk<MailRepository>(relaxed = true)
        every { repo.observeOutbox() } returns flowOf(emptyList())
        val vm = OutboxViewModel(repo)

        vm.cancel("m9")

        coVerify { repo.cancelOutboxMessage("m9") }
    }

    @Test
    fun `retry drains the outbox through the repository`() = runTest(dispatcher) {
        val repo = mockk<MailRepository>(relaxed = true)
        every { repo.observeOutbox() } returns flowOf(emptyList())
        val vm = OutboxViewModel(repo)

        vm.retry()

        coVerify { repo.retryOutbox() }
    }
}
