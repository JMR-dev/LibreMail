// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.drafts

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.R
import org.libremail.domain.model.Draft
import org.libremail.ui.FakeMailRepository
import org.libremail.ui.theme.LibreMailTheme

/**
 * End-to-end UI test for [DraftsScreen] + [DraftsViewModel] over an in-memory [FakeMailRepository]:
 * the empty state, the subject/recipient/body rendering (with the blank-field fallbacks), opening a
 * draft, and deleting one (which removes the row and records the deletion in the repository).
 */
@RunWith(AndroidJUnit4::class)
class DraftsScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun string(resId: Int) = composeTestRule.activity.getString(resId)

    private fun draft(id: String, to: String, subject: String, body: String) = Draft(
        id = id,
        accountId = "imap:a",
        to = to,
        cc = "",
        subject = subject,
        body = body,
        updatedAt = 1_000L,
    )

    private fun setContent(repo: FakeMailRepository, onOpenDraft: (String) -> Unit = {}) {
        val viewModel = DraftsViewModel(repo)
        composeTestRule.setContent {
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                DraftsScreen(onBack = {}, onOpenDraft = onOpenDraft, viewModel = viewModel)
            }
        }
    }

    private fun waitForText(text: String) = composeTestRule.waitUntil(5_000) {
        composeTestRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun emptyDrafts_showsEmptyState() {
        setContent(FakeMailRepository())

        composeTestRule.onNodeWithText(string(R.string.drafts_empty)).assertIsDisplayed()
    }

    @Test
    fun populatedDraft_showsSubjectRecipientAndBody() {
        setContent(
            FakeMailRepository(
                drafts = listOf(draft("d1", "alice@example.org", "Lunch plans", "See you at noon")),
            ),
        )
        waitForText("Lunch plans")

        composeTestRule.onNodeWithText("Lunch plans").assertIsDisplayed()
        composeTestRule.onNodeWithText("alice@example.org").assertIsDisplayed()
        composeTestRule.onNodeWithText("See you at noon").assertIsDisplayed()
    }

    @Test
    fun blankDraft_usesNoSubjectAndNoRecipientFallbacks() {
        setContent(FakeMailRepository(drafts = listOf(draft("d1", to = "", subject = "", body = ""))))
        waitForText(string(R.string.draft_no_subject))

        composeTestRule.onNodeWithText(string(R.string.draft_no_subject)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.draft_no_recipient)).assertIsDisplayed()
    }

    @Test
    fun tappingRow_invokesOnOpenDraft() {
        var opened: String? = null
        setContent(
            FakeMailRepository(drafts = listOf(draft("d1", "alice@example.org", "Lunch plans", "body"))),
            onOpenDraft = { opened = it },
        )
        waitForText("Lunch plans")

        composeTestRule.onNodeWithText("Lunch plans").performClick()

        composeTestRule.waitUntil(5_000) { opened == "d1" }
    }

    @Test
    fun tappingDelete_removesRow_andRecordsDeletion() {
        val repo = FakeMailRepository(
            drafts = listOf(
                draft("d1", "alice@example.org", "Lunch plans", "body"),
                draft("d2", "bob@example.org", "Second draft", "body"),
            ),
        )
        setContent(repo)
        waitForText("Lunch plans")

        // Delete the first draft: the fake removes it from the observed list, so its row disappears.
        composeTestRule.onAllNodesWithContentDescription(string(R.string.draft_delete))[0].performClick()

        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("Lunch plans").fetchSemanticsNodes().isEmpty()
        }
        assertEquals(listOf("d1"), repo.deletedDraftIds)
        composeTestRule.onNodeWithText("Second draft").assertIsDisplayed()
    }
}
