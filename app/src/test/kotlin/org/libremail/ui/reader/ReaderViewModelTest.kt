// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.reader

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
import org.libremail.domain.model.InlineImage
import org.libremail.domain.model.Message
import org.libremail.domain.model.ReplyMode
import org.libremail.domain.repository.MailRepository
import org.libremail.reporting.AppLog
import org.libremail.reporting.RingLogBuffer
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
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        // ReaderViewModel breadcrumbs open latency via AppLog on init (issue #358); android.util.Log is a
        // no-op stub under plain JVM tests, so mock it class-wide so VM construction never crashes.
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

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
    fun `inline images resolve into the same state update as the body so the reader renders once`() =
        runTest(dispatcher) {
            val htmlMessage = message.copy(isHtml = true, body = "<img src=\"cid:logo\">")
            val image = InlineImage("logo", "image/png", byteArrayOf(1, 2, 3))
            val repo = mockk<MailRepository>(relaxed = true)
            coEvery { repo.openMessage(messageId) } returns Result.success(htmlMessage)
            coEvery { repo.inlineImages(messageId) } returns listOf(image)
            every { repo.observeAttachments(messageId) } returns flowOf(emptyList())
            coEvery { repo.downloadedAttachmentParts(messageId) } returns emptySet()

            val vm = viewModel(repo)
            advanceUntilIdle()

            val state = vm.state.value
            // The body and its inline cid: image land together (loading already false), so HtmlBody
            // first composes with the image in place and loads the WebView exactly once (issue #186).
            assertEquals(false, state.loading)
            assertEquals(htmlMessage, state.message)
            assertEquals(setOf("logo"), state.inlineImages.keys)
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

    /** Builds a reader whose message loads and whose [MailRepository.buildReplyDraft] returns [draftId]. */
    private fun replyViewModel(repo: MailRepository, mode: ReplyMode, draftId: String = "draft-x"): ReaderViewModel {
        coEvery { repo.openMessage(messageId) } returns Result.success(message)
        every { repo.observeAttachments(messageId) } returns flowOf(emptyList())
        coEvery { repo.downloadedAttachmentParts(messageId) } returns emptySet()
        coEvery { repo.buildReplyDraft(messageId, mode) } returns Result.success(draftId)
        return viewModel(repo)
    }

    @Test
    fun `reply routes through buildReplyDraft and emits OpenCompose`() = runTest(dispatcher) {
        // #303: the open-email reply must reuse the mailbox's high-fidelity draft (quoted original +
        // signature), not a bare prefill — so it goes through buildReplyDraft and opens that draft.
        val repo = mockk<MailRepository>(relaxed = true)
        val vm = replyViewModel(repo, ReplyMode.REPLY, draftId = "draft-42")
        advanceUntilIdle()

        vm.reply(ReplyMode.REPLY)
        advanceUntilIdle()

        assertEquals(ReaderEvent.OpenCompose("draft-42"), vm.events.first())
        coVerify(exactly = 1) { repo.buildReplyDraft(messageId, ReplyMode.REPLY) }
    }

    @Test
    fun `forward passes the FORWARD mode to buildReplyDraft`() = runTest(dispatcher) {
        val repo = mockk<MailRepository>(relaxed = true)
        val vm = replyViewModel(repo, ReplyMode.FORWARD)
        advanceUntilIdle()

        vm.reply(ReplyMode.FORWARD)
        advanceUntilIdle()

        coVerify(exactly = 1) { repo.buildReplyDraft(messageId, ReplyMode.FORWARD) }
    }

    @Test
    fun `a rapid double-tap on reply builds only one draft`() = runTest(dispatcher) {
        // #304: composing is flipped synchronously, so the second tap (before the first draft resolves)
        // is dropped rather than building a second draft and opening compose twice.
        val repo = mockk<MailRepository>(relaxed = true)
        val vm = replyViewModel(repo, ReplyMode.REPLY)
        advanceUntilIdle()

        vm.reply(ReplyMode.REPLY)
        vm.reply(ReplyMode.REPLY)
        advanceUntilIdle()

        coVerify(exactly = 1) { repo.buildReplyDraft(messageId, ReplyMode.REPLY) }
    }

    @Test
    fun `a failed reply emits ComposeFailed and clears the composing flag`() = runTest(dispatcher) {
        val repo = mockk<MailRepository>(relaxed = true)
        coEvery { repo.openMessage(messageId) } returns Result.success(message)
        every { repo.observeAttachments(messageId) } returns flowOf(emptyList())
        coEvery { repo.downloadedAttachmentParts(messageId) } returns emptySet()
        coEvery { repo.buildReplyDraft(messageId, ReplyMode.REPLY) } returns Result.failure(RuntimeException("boom"))

        val vm = viewModel(repo)
        advanceUntilIdle()
        vm.reply(ReplyMode.REPLY)
        advanceUntilIdle()

        assertEquals(ReaderEvent.ComposeFailed("boom"), vm.events.first())
        assertEquals(false, vm.state.value.composing)
    }

    @Test
    fun `a successful load breadcrumbs the reader-ready latency`() = runTest(dispatcher) {
        val buffer = RingLogBuffer()
        AppLog.install(buffer)
        val repo = mockk<MailRepository>(relaxed = true)
        coEvery { repo.openMessage(messageId) } returns Result.success(message)
        every { repo.observeAttachments(messageId) } returns flowOf(emptyList())
        coEvery { repo.downloadedAttachmentParts(messageId) } returns emptySet()

        viewModel(repo)
        advanceUntilIdle()

        val messages = buffer.snapshot().map { it.message }
        assertTrue(
            messages.any { it.startsWith("reader ready took=") && it.contains("html=") },
            "messages=$messages",
        )
    }
}
