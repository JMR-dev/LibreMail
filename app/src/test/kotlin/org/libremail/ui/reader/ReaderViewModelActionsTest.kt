// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.reader

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
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
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelActionsTest {

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

    private fun viewModel(repo: MailRepository, loadRemoteImages: Boolean = false): ReaderViewModel {
        val settings = mockk<SettingsRepository>()
        every { settings.settings } returns flowOf(AppSettings(loadRemoteImages = loadRemoteImages))
        return ReaderViewModel(SavedStateHandle(mapOf(Routes.READER_ARG_ID to messageId)), repo, settings)
    }

    private fun loadedRepo(): MailRepository {
        val repo = mockk<MailRepository>(relaxed = true)
        coEvery { repo.openMessage(messageId) } returns Result.success(message)
        every { repo.observeAttachments(messageId) } returns flowOf(listOf(attachment(0)))
        coEvery { repo.downloadedAttachmentParts(messageId) } returns emptySet()
        return repo
    }

    @Test
    fun `the global load-remote-images default is honoured on open`() = runTest(dispatcher) {
        val vm = viewModel(loadedRepo(), loadRemoteImages = true)
        advanceUntilIdle()

        assertTrue(vm.state.value.loadRemoteImages)
    }

    @Test
    fun `loadRemoteImages opts this message in on demand`() = runTest(dispatcher) {
        val vm = viewModel(loadedRepo(), loadRemoteImages = false)
        advanceUntilIdle()
        assertFalse(vm.state.value.loadRemoteImages)

        vm.loadRemoteImages()

        assertTrue(vm.state.value.loadRemoteImages)
    }

    @Test
    fun `a failed open surfaces the error and stops loading`() = runTest(dispatcher) {
        val repo = mockk<MailRepository>(relaxed = true)
        coEvery { repo.openMessage(messageId) } returns Result.failure(IllegalStateException("network down"))
        every { repo.observeAttachments(messageId) } returns flowOf(emptyList())
        coEvery { repo.downloadedAttachmentParts(messageId) } returns emptySet()

        val vm = viewModel(repo)
        advanceUntilIdle()

        assertEquals("network down", vm.state.value.error)
        assertFalse(vm.state.value.loading)
        assertNull(vm.state.value.message)
    }

    @Test
    fun `a successful download opens the file and clears the in-flight marker`() = runTest(dispatcher) {
        val repo = loadedRepo()
        val cached = File("cached.bin")
        coEvery { repo.downloadAttachment(messageId, 0) } returns Result.success(cached)
        val vm = viewModel(repo)
        advanceUntilIdle()

        vm.events.test {
            vm.downloadAttachment(attachment(0))
            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is ReaderEvent.OpenFile && event.file == cached)
            assertTrue(0 in vm.state.value.downloaded)
            assertFalse(0 in vm.state.value.downloading)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a failed download emits DownloadFailed and clears the in-flight marker`() = runTest(dispatcher) {
        val repo = loadedRepo()
        coEvery { repo.downloadAttachment(messageId, 0) } returns Result.failure(RuntimeException("boom"))
        val vm = viewModel(repo)
        advanceUntilIdle()

        vm.events.test {
            vm.downloadAttachment(attachment(0))
            advanceUntilIdle()

            assertEquals(ReaderEvent.DownloadFailed("file0.bin"), awaitItem())
            assertFalse(0 in vm.state.value.downloading)
            assertFalse(0 in vm.state.value.downloaded)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a second download of an in-flight attachment is ignored`() = runTest(dispatcher) {
        val repo = loadedRepo()
        coEvery { repo.downloadAttachment(messageId, 0) } returns Result.success(File("cached.bin"))
        val vm = viewModel(repo)
        advanceUntilIdle()

        // The first call marks the part in-flight synchronously; the second sees it and bails out.
        vm.downloadAttachment(attachment(0))
        vm.downloadAttachment(attachment(0))
        advanceUntilIdle()

        coVerify(exactly = 1) { repo.downloadAttachment(messageId, 0) }
    }

    @Test
    fun `toggleStar flips the star optimistically and persists it`() = runTest(dispatcher) {
        val repo = loadedRepo()
        val vm = viewModel(repo)
        advanceUntilIdle()

        vm.toggleStar()
        advanceUntilIdle()

        assertTrue(vm.state.value.message!!.isStarred)
        coVerify { repo.setStarred(messageId, true) }
    }

    @Test
    fun `toggleStar does nothing before the message has loaded`() = runTest(dispatcher) {
        val repo = mockk<MailRepository>(relaxed = true)
        coEvery { repo.openMessage(messageId) } returns Result.failure(IllegalStateException("x"))
        every { repo.observeAttachments(messageId) } returns flowOf(emptyList())
        coEvery { repo.downloadedAttachmentParts(messageId) } returns emptySet()
        val vm = viewModel(repo)
        advanceUntilIdle()

        vm.toggleStar()
        advanceUntilIdle()

        coVerify(exactly = 0) { repo.setStarred(any(), any()) }
    }

    @Test
    fun `delete removes the message and marks the state deleted`() = runTest(dispatcher) {
        val repo = loadedRepo()
        val vm = viewModel(repo)
        advanceUntilIdle()

        vm.delete()
        advanceUntilIdle()

        assertTrue(vm.state.value.deleted)
        coVerify { repo.deleteMessage(messageId) }
    }

    @Test
    fun `ReaderUiState and its events carry value semantics`() {
        val state = ReaderUiState(
            loading = false,
            message = message,
            attachments = listOf(attachment(0)),
            downloading = setOf(1),
            downloaded = setOf(0),
            loadRemoteImages = true,
            deleted = true,
            error = "e",
        )
        assertEquals(state, state.copy())
        assertEquals(state.hashCode(), state.copy().hashCode())
        assertTrue(state.toString().contains("error"))
        assertNotEquals(state, state.copy(loading = true))
        assertNotEquals(state, state.copy(error = null))

        val open = ReaderEvent.OpenFile(File("a"), "text/plain", "a.txt")
        val (file, mimeType, name) = open
        assertEquals(File("a"), file)
        assertEquals("text/plain", mimeType)
        assertEquals("a.txt", name)
        assertEquals(open, open.copy())
        assertEquals(open.hashCode(), open.copy().hashCode())
        assertTrue(open.toString().contains("a.txt"))
        assertNotEquals(open, open.copy(name = "b.txt"))
        assertNotEquals<ReaderEvent>(open, ReaderEvent.DownloadFailed("a.txt"))

        val failed = ReaderEvent.DownloadFailed("x")
        assertEquals("x", failed.name)
        assertEquals(failed, failed.copy())
        assertNotEquals(failed, failed.copy(name = "y"))
        assertTrue(failed.toString().contains("x"))
    }
}
