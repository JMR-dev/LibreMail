// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.reader

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.R
import org.libremail.data.settings.AppSettings
import org.libremail.data.settings.SettingsRepository
import org.libremail.domain.model.Attachment
import org.libremail.domain.model.Message
import org.libremail.domain.model.ReplyMode
import org.libremail.domain.repository.MailRepository
import org.libremail.ui.navigation.Routes
import org.libremail.ui.theme.LibreMailTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric JVM port of the reader screen's chrome (issue #381, umbrella #373): drives the real
 * [ReaderViewModel] over a mocked [MailRepository] / [SettingsRepository] on the JVM under
 * [RobolectricTestRunner] via the v2 `createComposeRule()` — no emulator — so [ReaderScreen] counts
 * toward JaCoCo's JVM-testable surface. Mirrors the instrumented `ReaderScreenTest` for the
 * interactions (attachments accordion, reply/reply-all/forward, star, delete) and additionally
 * exercises the loading / plain-text / empty / error branches and the remote-images banner.
 *
 * **WebView caveat.** The HTML body renders through [HtmlBody] — a hardened `WebView` embedded via
 * `AndroidView`. Under Robolectric the `WebView` is a non-rendering *shadow*, so these tests never
 * assert WebView-rendered HTML. The remote-images banner (which only appears for an HTML message) is
 * driven with an HTML message whose body is **blank**: that hits the `isHtml && !loadRemoteImages`
 * banner branch and the blank-body placeholder branch *without* invoking [HtmlBody] at all, so no
 * WebView shadow is instantiated. `HtmlBody.kt` therefore stays out of this batch — its logic
 * (`cidKey`/`resolveInlineImage`/`wrapHtml`/`toCssHex`) is already JVM-covered by `HtmlBodyTest` and
 * `InlineImageResolverTest`, and its `WebView` render is not meaningfully JVM-testable. The
 * instrumented `ReaderScreenTest` stays as the on-device E2E. See
 * [org.libremail.ui.onboarding.AddAnotherAccountScreenJvmTest] for the pattern.
 *
 * `@Config(sdk = [36])` because compileSdk/targetSdk 37 (preview) has no Robolectric 4.16 sandbox;
 * `@GraphicsMode(NATIVE)` is required for Compose to render under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ReaderScreenJvmTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun string(resId: Int): String = context.getString(resId)

    private fun seeMore(extraCount: Int): String =
        context.resources.getQuantityString(R.plurals.attachments_see_more, extraCount, extraCount)

    private val messageId = "imap:a:INBOX:1"

    private val plainMessage = Message(
        id = messageId, accountId = "imap:a", sender = "Sender", senderEmail = "s@example.org",
        subject = "Subject line", snippet = "", body = "Hello plain body", isHtml = false,
        timestampMillis = 1_000L, isRead = true, isStarred = false,
    )

    private fun attachment(partIndex: Int, filename: String, sizeBytes: Long = 1_000L) =
        Attachment(messageId, partIndex, filename, "application/pdf", sizeBytes)

    /** The draft id the reader last asked to open compose on (via OpenCompose), or null. */
    private var openedDraftId: String? = null

    /** Set when the reader invokes onBack (after a delete, or its own back button). */
    private var backInvoked = false

    /**
     * A relaxed [MailRepository] whose reads return [message] (or [openResult]) plus the given
     * [attachments] / [downloadedParts]. Stubs the value-class-returning suspend functions explicitly
     * — MockK can't synthesize a relaxed [Result] — so `buildReplyDraft` yields `draft-<id>` (matching
     * the instrumented fake) and `downloadAttachment` fails (the failure path is what's driven here;
     * the success path launches a FileProvider intent that isn't meaningful on the JVM).
     */
    private fun mailRepo(
        openResult: Result<Message> = Result.success(plainMessage),
        attachments: List<Attachment> = emptyList(),
        downloadedParts: Set<Int> = emptySet(),
    ): MailRepository {
        val repo = mockk<MailRepository>(relaxed = true)
        coEvery { repo.openMessage(messageId) } returns openResult
        every { repo.observeAttachments(messageId) } returns flowOf(attachments)
        coEvery { repo.downloadedAttachmentParts(messageId) } returns downloadedParts
        coEvery { repo.inlineImages(messageId) } returns emptyList()
        coEvery { repo.setStarred(any(), any()) } returns Result.success(Unit)
        coEvery { repo.deleteMessage(any()) } returns Result.success(Unit)
        coEvery { repo.buildReplyDraft(eq(messageId), any()) } returns Result.success("draft-$messageId")
        coEvery { repo.downloadAttachment(any(), any()) } returns Result.failure(RuntimeException("no network"))
        return repo
    }

    private fun settingsRepo(loadRemoteImages: Boolean = false): SettingsRepository {
        val settings = mockk<SettingsRepository>()
        every { settings.settings } returns flowOf(AppSettings(loadRemoteImages = loadRemoteImages))
        return settings
    }

    private fun setContent(repo: MailRepository = mailRepo(), settings: SettingsRepository = settingsRepo()) {
        val viewModel = ReaderViewModel(
            SavedStateHandle(mapOf(Routes.READER_ARG_ID to messageId)),
            repo,
            settings,
        )
        composeTestRule.setContent {
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                ReaderScreen(
                    onBack = { backInvoked = true },
                    onOpenCompose = { openedDraftId = it },
                    viewModel = viewModel,
                )
            }
        }
    }

    /** Waits until the message has loaded — the Reply action appears once `state.message` is non-null. */
    private fun awaitLoaded() {
        composeTestRule.waitUntil(TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(string(R.string.reader_reply)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun loading_showsProgressIndicator_andNoActions() {
        // openMessage never returns, so the initial loading=true state stays and the spinner shows.
        val repo = mockk<MailRepository>(relaxed = true)
        coEvery { repo.openMessage(messageId) } coAnswers { awaitCancellation() }
        every { repo.observeAttachments(messageId) } returns flowOf(emptyList())
        coEvery { repo.downloadedAttachmentParts(messageId) } returns emptySet()
        setContent(repo)

        composeTestRule
            .onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .assertExists()
        // No message yet, so the toolbar actions aren't rendered.
        composeTestRule.onNodeWithText(string(R.string.reader_reply)).assertDoesNotExist()
    }

    @Test
    fun plainTextMessage_rendersTitleHeaderAndBody() {
        setContent()
        awaitLoaded()

        composeTestRule.onNodeWithText(string(R.string.title_reader)).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(string(R.string.action_back)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Subject line").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sender").assertIsDisplayed()
        composeTestRule.onNodeWithText("s@example.org").assertIsDisplayed()
        composeTestRule.onNodeWithText("Hello plain body").assertIsDisplayed()
    }

    @Test
    fun blankBody_showsNoTextPlaceholder() {
        setContent(mailRepo(openResult = Result.success(plainMessage.copy(body = ""))))
        awaitLoaded()

        composeTestRule.onNodeWithText(string(R.string.reader_empty)).assertIsDisplayed()
    }

    @Test
    fun loadFailure_showsErrorText() {
        setContent(mailRepo(openResult = Result.failure(RuntimeException("Load failed"))))

        // The message never loads, so awaitLoaded() would time out; wait for the error branch instead.
        composeTestRule.waitUntil(TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText("Load failed").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Load failed").assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.reader_reply)).assertDoesNotExist()
    }

    @Test
    fun tappingStar_togglesStarredViaRepository() {
        val repo = mailRepo()
        setContent(repo)
        awaitLoaded()

        composeTestRule.onNodeWithContentDescription(string(R.string.reader_star)).performClick()
        composeTestRule.waitForIdle()

        // The starting message is unstarred, so the tap flips it to starred.
        coVerify(exactly = 1) { repo.setStarred(messageId, true) }
    }

    @Test
    fun tappingDelete_deletesAndInvokesOnBack() {
        val repo = mailRepo()
        setContent(repo)
        awaitLoaded()

        composeTestRule.onNodeWithContentDescription(string(R.string.reader_delete)).performClick()
        composeTestRule.waitUntil(TIMEOUT_MS) { backInvoked }

        coVerify(exactly = 1) { repo.deleteMessage(messageId) }
    }

    @Test
    fun tappingReply_buildsReplyDraftAndOpensCompose() {
        val repo = mailRepo()
        setContent(repo)
        awaitLoaded()

        composeTestRule.onNodeWithText(string(R.string.reader_reply)).performClick()
        composeTestRule.waitUntil(TIMEOUT_MS) { openedDraftId != null }

        // #303: reply goes through buildReplyDraft (quoted original + signature), then opens that draft.
        assertEquals("draft-$messageId", openedDraftId)
        coVerify(exactly = 1) { repo.buildReplyDraft(messageId, ReplyMode.REPLY) }
    }

    @Test
    fun replyAll_viaOverflow_buildsReplyAllDraft() {
        val repo = mailRepo()
        setContent(repo)
        awaitLoaded()

        composeTestRule.onNodeWithContentDescription(string(R.string.action_more)).performClick()
        composeTestRule.onNodeWithText(string(R.string.action_reply_all)).performClick()
        composeTestRule.waitUntil(TIMEOUT_MS) { openedDraftId != null }

        coVerify(exactly = 1) { repo.buildReplyDraft(messageId, ReplyMode.REPLY_ALL) }
    }

    @Test
    fun forward_viaOverflow_buildsForwardDraft() {
        val repo = mailRepo()
        setContent(repo)
        awaitLoaded()

        composeTestRule.onNodeWithContentDescription(string(R.string.action_more)).performClick()
        composeTestRule.onNodeWithText(string(R.string.action_forward)).performClick()
        composeTestRule.waitUntil(TIMEOUT_MS) { openedDraftId != null }

        coVerify(exactly = 1) { repo.buildReplyDraft(messageId, ReplyMode.FORWARD) }
    }

    @Test
    fun singleAttachment_showsRowWithoutAccordion() {
        // 2_000_000 bytes exercises formatSize's MB branch ("1.9 MB").
        setContent(mailRepo(attachments = listOf(attachment(0, "solo.pdf", sizeBytes = 2_000_000L))))
        awaitLoaded()

        composeTestRule.onNodeWithText(string(R.string.attachments_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText("solo.pdf").assertIsDisplayed()
        composeTestRule.onNodeWithText("1.9 MB").assertIsDisplayed()
        // A lone attachment has no "See more" accordion.
        composeTestRule.onNodeWithContentDescription(string(R.string.attachments_expand)).assertDoesNotExist()
    }

    @Test
    fun multipleAttachments_collapseExtrasUntilExpanded() {
        // "noext" (no extension, 0 bytes) exercises fileExtension's "FILE" fallback and formatSize's
        // empty branch; "three.pdf" at 2048 bytes exercises the KB branch once revealed.
        setContent(
            mailRepo(
                attachments = listOf(
                    attachment(0, "one.pdf", sizeBytes = 1_000L),
                    attachment(1, "noext", sizeBytes = 0L),
                    attachment(2, "three.pdf", sizeBytes = 2_048L),
                ),
            ),
        )
        awaitLoaded()

        // First row shown; the two extras hide behind the collapsed accordion.
        composeTestRule.onNodeWithText("one.pdf").assertIsDisplayed()
        composeTestRule.onNodeWithText(seeMore(2)).assertIsDisplayed()
        composeTestRule.onNodeWithText("noext").assertDoesNotExist()
        composeTestRule.onNodeWithText("three.pdf").assertDoesNotExist()

        // Tapping the control reveals the remaining rows.
        composeTestRule.onNodeWithText(seeMore(2)).performClick()
        composeTestRule.waitUntil(TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText("three.pdf").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("noext").assertIsDisplayed()
        composeTestRule.onNodeWithText("three.pdf").assertIsDisplayed()
    }

    @Test
    fun downloadedAttachment_showsDownloadedIndicator() {
        // 2048 bytes exercises formatSize's KB branch ("2 KB").
        setContent(
            mailRepo(
                attachments = listOf(attachment(0, "report.pdf", sizeBytes = 2_048L)),
                downloadedParts = setOf(0),
            ),
        )
        awaitLoaded()

        composeTestRule.onNodeWithText("report.pdf").assertIsDisplayed()
        composeTestRule.onNodeWithText("2 KB").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(string(R.string.attachment_downloaded)).assertIsDisplayed()
    }

    @Test
    fun tappingAttachment_whoseDownloadFails_showsSnackbar() {
        setContent(mailRepo(attachments = listOf(attachment(0, "report.pdf"))))
        awaitLoaded()

        composeTestRule.onNodeWithText("report.pdf").performClick()

        val failed = string(R.string.attachment_download_failed).format("report.pdf")
        composeTestRule.waitUntil(TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(failed).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(failed).assertIsDisplayed()
    }

    @Test
    fun htmlMessageBlankBody_showsRemoteImagesBanner_thenHidesOnShowImages() {
        // An HTML message with a blank body hits the `isHtml && !loadRemoteImages` banner branch and the
        // blank-body placeholder — without invoking HtmlBody's (non-rendering) WebView shadow.
        setContent(mailRepo(openResult = Result.success(plainMessage.copy(isHtml = true, body = ""))))
        awaitLoaded()

        composeTestRule.onNodeWithText(string(R.string.reader_images_blocked)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.reader_empty)).assertIsDisplayed()

        composeTestRule.onNodeWithText(string(R.string.reader_show_images)).performClick()
        composeTestRule.waitForIdle()

        // Opting in clears the banner.
        composeTestRule.onNodeWithText(string(R.string.reader_images_blocked)).assertDoesNotExist()
    }

    @Test
    fun htmlMessage_withLoadRemoteImagesPreference_showsNoBanner() {
        // The "load remote images by default" preference flips state.loadRemoteImages on init, so the
        // banner never appears.
        setContent(
            repo = mailRepo(openResult = Result.success(plainMessage.copy(isHtml = true, body = ""))),
            settings = settingsRepo(loadRemoteImages = true),
        )
        awaitLoaded()

        composeTestRule.onNodeWithText(string(R.string.reader_images_blocked)).assertDoesNotExist()
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
