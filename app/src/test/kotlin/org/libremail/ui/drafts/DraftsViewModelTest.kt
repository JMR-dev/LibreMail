// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.drafts

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
import org.libremail.domain.model.Draft
import org.libremail.domain.repository.MailRepository
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class DraftsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun draft(id: String) = Draft(
        id = id,
        accountId = "imap:a",
        to = "to@example.org",
        cc = "",
        subject = "Subject",
        body = "Body",
        updatedAt = 1L,
    )

    @Test
    fun `drafts mirrors the repository's draft stream once collected`() = runTest(dispatcher) {
        val repo = mockk<MailRepository>(relaxed = true)
        every { repo.observeDrafts() } returns MutableStateFlow(listOf(draft("d1"), draft("d2")))
        val vm = DraftsViewModel(repo)

        backgroundScope.launch { vm.drafts.collect {} }
        runCurrent()

        assertEquals(listOf("d1", "d2"), vm.drafts.value.map { it.id })
    }

    @Test
    fun `drafts starts empty before the stream emits`() = runTest(dispatcher) {
        val repo = mockk<MailRepository>(relaxed = true)
        every { repo.observeDrafts() } returns flowOf(emptyList())
        val vm = DraftsViewModel(repo)

        assertEquals(emptyList(), vm.drafts.value)
    }

    @Test
    fun `deleteDraft delegates to the repository`() = runTest(dispatcher) {
        val repo = mockk<MailRepository>(relaxed = true)
        every { repo.observeDrafts() } returns flowOf(emptyList())
        val vm = DraftsViewModel(repo)

        vm.deleteDraft("d9")

        coVerify { repo.deleteDraft("d9") }
    }
}
