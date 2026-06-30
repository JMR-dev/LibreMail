// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.reader

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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

/** End-to-end test that the reader marks an already-cached attachment as downloaded. */
@RunWith(AndroidJUnit4::class)
class ReaderScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun string(resId: Int) = composeTestRule.activity.getString(resId)

    private val messageId = "imap:a:INBOX:1"
    private val message = Message(
        id = messageId, accountId = "imap:a", sender = "Sender", senderEmail = "s@example.org",
        subject = "Subject", snippet = "", body = "Hello body", isHtml = false, timestampMillis = 1_000L,
        isRead = true, isStarred = false,
    )

    @Test
    fun reader_showsDownloadedIndicator_forCachedAttachment() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val repo = FakeMailRepository(
            messages = listOf(message),
            attachments = listOf(Attachment(messageId, 0, "report.pdf", "application/pdf", 1234L)),
            downloadedParts = setOf(0),
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
            composeTestRule.onAllNodesWithText("report.pdf").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithContentDescription(string(R.string.attachment_downloaded)).assertIsDisplayed()
    }
}
