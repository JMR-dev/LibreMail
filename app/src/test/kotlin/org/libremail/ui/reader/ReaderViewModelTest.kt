// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.reader

import androidx.lifecycle.SavedStateHandle
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.data.settings.AppSettings
import org.libremail.data.settings.SettingsRepository
import org.libremail.domain.model.Attachment
import org.libremail.domain.model.Message
import org.libremail.domain.repository.MailRepository
import org.libremail.ui.navigation.Routes
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val messageId = "acct:INBOX:1"

    private val message = Message(
        id = messageId, accountId = "acct", sender = "A", senderEmail = "a@example.org",
        subject = "s", snippet = "", body = "Body", isHtml = false, timestampMillis = 1,
        isRead = true, isStarred = false,
    )

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun attachment(partIndex: Int) =
        Attachment(messageId, partIndex, "file$partIndex.bin", "application/octet-stream", 10L)

    private fun viewModel(repo: MailRepository): ReaderViewModel {
        val settings = mockk<SettingsRepository>()
        every { settings.settings } returns flowOf(AppSettings())
        return ReaderViewModel(SavedStateHandle(mapOf(Routes.READER_ARG_ID to messageId)), repo, settings)
    }

    @Test
    fun `downloaded set reflects the repository's cached parts on load`() = runTest(dispatcher) {
        val repo = mockk<MailRepository>(relaxed = true)
        coEvery { repo.openMessage(messageId) } returns Result.success(message)
        every { repo.observeAttachments(messageId) } returns flowOf(listOf(attachment(0), attachment(1)))
        coEvery { repo.downloadedAttachmentParts(messageId) } returns setOf(0)

        val vm = viewModel(repo)
        advanceUntilIdle()

        assertEquals(setOf(0), vm.state.value.downloaded)
    }

    @Test
    fun `downloading an attachment adds its part to the downloaded set`() = runTest(dispatcher) {
        val repo = mockk<MailRepository>(relaxed = true)
        coEvery { repo.openMessage(messageId) } returns Result.success(message)
        every { repo.observeAttachments(messageId) } returns flowOf(listOf(attachment(0)))
        coEvery { repo.downloadedAttachmentParts(messageId) } returns emptySet()
        coEvery { repo.downloadAttachment(messageId, 0) } returns Result.success(File("cached.bin"))

        val vm = viewModel(repo)
        advanceUntilIdle()
        vm.downloadAttachment(attachment(0))
        advanceUntilIdle()

        assertTrue(0 in vm.state.value.downloaded)
    }
}
