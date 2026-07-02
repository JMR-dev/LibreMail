// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.reader

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.R
import org.libremail.data.settings.SettingsRepository
import org.libremail.domain.model.Attachment
import org.libremail.domain.model.Message
import org.libremail.ui.FakeMailRepository
import org.libremail.ui.navigation.Routes
import org.libremail.ui.theme.LibreMailTheme

/** Compose UI tests for the reader's attachment list: the downloaded indicator and the accordion. */
@RunWith(AndroidJUnit4::class)
class ReaderScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun string(resId: Int) = composeTestRule.activity.getString(resId)

    private fun seeMore(extraCount: Int) =
        composeTestRule.activity.resources.getQuantityString(R.plurals.attachments_see_more, extraCount, extraCount)

    private val messageId = "imap:a:INBOX:1"
    private val message = Message(
        id = messageId, accountId = "imap:a", sender = "Sender", senderEmail = "s@example.org",
        subject = "Subject", snippet = "", body = "Hello body", isHtml = false, timestampMillis = 1_000L,
        isRead = true, isStarred = false,
    )

    private fun attachment(partIndex: Int, filename: String) =
        Attachment(messageId, partIndex, filename, "application/pdf", 1_000L)

    /** Renders [ReaderScreen] for the fixed [message] with the given [attachments] and awaits load. */
    private fun renderReader(attachments: List<Attachment>, downloadedParts: Set<Int> = emptySet()) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val repo = FakeMailRepository(
            messages = listOf(message),
            attachments = attachments,
            downloadedParts = downloadedParts,
        )
        val viewModel = ReaderViewModel(
            SavedStateHandle(mapOf(Routes.READER_ARG_ID to messageId)),
            repo,
            SettingsRepository(context),
        )
        composeTestRule.setContent {
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                ReaderScreen(onBack = {}, onReply = { _, _, _ -> }, viewModel = viewModel)
            }
        }
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText(attachments.first().filename).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun reader_showsDownloadedIndicator_forCachedAttachment() {
        renderReader(listOf(attachment(0, "report.pdf")), downloadedParts = setOf(0))

        composeTestRule.onNodeWithText("report.pdf").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(string(R.string.attachment_downloaded)).assertIsDisplayed()
    }

    @Test
    fun reader_singleAttachment_showsNoAccordion() {
        renderReader(listOf(attachment(0, "solo.pdf")))

        composeTestRule.onNodeWithText("solo.pdf").assertIsDisplayed()
        // A lone attachment has no "See more" control.
        composeTestRule.onNodeWithContentDescription(string(R.string.attachments_expand)).assertDoesNotExist()
    }

    @Test
    fun reader_multipleAttachments_collapseExtrasUntilExpanded() {
        renderReader(listOf(attachment(0, "one.pdf"), attachment(1, "two.pdf"), attachment(2, "three.pdf")))

        // First row shown; the two extras are hidden behind the collapsed accordion.
        composeTestRule.onNodeWithText("one.pdf").assertIsDisplayed()
        composeTestRule.onNodeWithText(seeMore(2)).assertIsDisplayed()
        composeTestRule.onNodeWithText("two.pdf").assertDoesNotExist()
        composeTestRule.onNodeWithText("three.pdf").assertDoesNotExist()

        // Tapping the control reveals the remaining rows.
        composeTestRule.onNodeWithText(seeMore(2)).performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("two.pdf").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("two.pdf").assertIsDisplayed()
        composeTestRule.onNodeWithText("three.pdf").assertIsDisplayed()
    }

    @Test
    fun reader_twoAttachments_useSingularPlural() {
        renderReader(listOf(attachment(0, "a.pdf"), attachment(1, "b.pdf")))

        // Exactly one extra: the singular plural form, e.g. "See 1 more attachment".
        composeTestRule.onNodeWithText(seeMore(1)).assertIsDisplayed()
        composeTestRule.onNodeWithText("b.pdf").assertDoesNotExist()
    }
}
