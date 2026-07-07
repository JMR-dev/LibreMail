// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.drafts

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.R
import org.libremail.domain.model.Draft
import org.libremail.domain.repository.MailRepository
import org.libremail.ui.theme.LibreMailTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric JVM port of the instrumented [DraftsScreenTest] (batch 4/9 of umbrella #373): drives
 * [DraftsScreen] + the real [DraftsViewModel] over a mocked [MailRepository] on the JVM via the v2
 * `createComposeRule()` — no emulator — so [DraftsScreen]'s render + interaction code counts toward
 * JaCoCo's JVM-testable surface. Covers the empty state, the subject/recipient/body rendering with
 * the blank-field fallbacks, opening a draft, and deleting one (row disappears + delegates to the
 * repository). The instrumented [DraftsScreenTest] stays as the on-device E2E. See
 * [org.libremail.ui.onboarding.AddAnotherAccountScreenJvmTest] for the pattern.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class DraftsScreenJvmTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun string(resId: Int) = context.getString(resId)

    private fun draft(id: String, to: String, subject: String, body: String) = Draft(
        id = id,
        accountId = "imap:a",
        to = to,
        cc = "",
        subject = subject,
        body = body,
        updatedAt = 1_000L,
    )

    /**
     * Builds a relaxed [MailRepository] whose observed drafts are backed by a [MutableStateFlow], so a
     * `deleteDraft` mutates the observed list and the row actually disappears (mirroring the DB-backed
     * repository's reactivity), and constructs the real [DraftsViewModel] over it.
     */
    private fun setContent(drafts: List<Draft>, onOpenDraft: (String) -> Unit = {}): MailRepository {
        val draftsFlow = MutableStateFlow(drafts)
        val repo = mockk<MailRepository>(relaxed = true)
        every { repo.observeDrafts() } returns draftsFlow
        coEvery { repo.deleteDraft(any()) } answers {
            val id = firstArg<String>()
            draftsFlow.value = draftsFlow.value.filterNot { it.id == id }
        }
        val viewModel = DraftsViewModel(repo)
        composeTestRule.setContent {
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                DraftsScreen(onBack = {}, onOpenDraft = onOpenDraft, viewModel = viewModel)
            }
        }
        return repo
    }

    private fun waitForText(text: String) = composeTestRule.waitUntil(TIMEOUT_MS) {
        composeTestRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun emptyDrafts_showsEmptyState() {
        setContent(emptyList())

        composeTestRule.onNodeWithText(string(R.string.drafts_empty)).assertIsDisplayed()
    }

    @Test
    fun populatedDraft_showsSubjectRecipientAndBody() {
        setContent(listOf(draft("d1", "alice@example.org", "Lunch plans", "See you at noon")))
        waitForText("Lunch plans")

        composeTestRule.onNodeWithText("Lunch plans").assertIsDisplayed()
        composeTestRule.onNodeWithText("alice@example.org").assertIsDisplayed()
        composeTestRule.onNodeWithText("See you at noon").assertIsDisplayed()
    }

    @Test
    fun blankDraft_usesNoSubjectAndNoRecipientFallbacks() {
        setContent(listOf(draft("d1", to = "", subject = "", body = "")))
        waitForText(string(R.string.draft_no_subject))

        composeTestRule.onNodeWithText(string(R.string.draft_no_subject)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.draft_no_recipient)).assertIsDisplayed()
    }

    @Test
    fun tappingRow_invokesOnOpenDraft() {
        var opened: String? = null
        setContent(
            listOf(draft("d1", "alice@example.org", "Lunch plans", "body")),
            onOpenDraft = { opened = it },
        )
        waitForText("Lunch plans")

        composeTestRule.onNodeWithText("Lunch plans").performClick()

        composeTestRule.waitUntil(TIMEOUT_MS) { opened == "d1" }
    }

    @Test
    fun tappingDelete_removesRow_andRecordsDeletion() {
        val repo = setContent(
            listOf(
                draft("d1", "alice@example.org", "Lunch plans", "body"),
                draft("d2", "bob@example.org", "Second draft", "body"),
            ),
        )
        waitForText("Lunch plans")

        // Delete the first draft: the fake removes it from the observed list, so its row disappears.
        composeTestRule.onAllNodesWithContentDescription(string(R.string.draft_delete))[0].performClick()

        composeTestRule.waitUntil(TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText("Lunch plans").fetchSemanticsNodes().isEmpty()
        }
        coVerify(exactly = 1) { repo.deleteDraft("d1") }
        composeTestRule.onNodeWithText("Second draft").assertIsDisplayed()
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
