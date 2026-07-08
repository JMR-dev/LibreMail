// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.outbox

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
import org.libremail.domain.model.OutboxMessage
import org.libremail.ui.FakeMailRepository
import org.libremail.ui.theme.LibreMailTheme

/**
 * End-to-end UI test for [OutboxScreen] + [OutboxViewModel] over an in-memory [FakeMailRepository]:
 * the empty state, queued-vs-failed row rendering, and the cancel/retry actions round-tripping
 * through the repository (cancel removes the row; retry calls back into the repo).
 */
@RunWith(AndroidJUnit4::class)
class OutboxScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun string(resId: Int) = composeTestRule.activity.getString(resId)

    private fun queued(id: String, subject: String, to: String) =
        OutboxMessage(id = id, to = to, subject = subject, body = "body", createdAt = 1_000L, lastError = null)

    private fun failed(id: String, subject: String, to: String) =
        OutboxMessage(id = id, to = to, subject = subject, body = "body", createdAt = 2_000L, lastError = "SMTP 550")

    private fun setContent(repo: FakeMailRepository) {
        val viewModel = OutboxViewModel(repo)
        composeTestRule.setContent {
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                OutboxScreen(onBack = {}, viewModel = viewModel)
            }
        }
    }

    private fun waitForText(text: String) = composeTestRule.waitUntil(5_000) {
        composeTestRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun emptyOutbox_showsEmptyState_andNoRetryAction() {
        setContent(FakeMailRepository())

        composeTestRule.onNodeWithText(string(R.string.outbox_empty)).assertIsDisplayed()
        // Retry only appears when there is something to retry.
        composeTestRule.onNodeWithText(string(R.string.outbox_retry)).assertDoesNotExist()
    }

    @Test
    fun populatedOutbox_showsQueuedAndFailedRows_andRetryAction() {
        setContent(
            FakeMailRepository(
                outbox = listOf(
                    queued("q", "Queued mail", "alice@example.org"),
                    failed("f", "Failed mail", "bob@example.org"),
                ),
            ),
        )
        waitForText("Queued mail")

        composeTestRule.onNodeWithText("Queued mail").assertIsDisplayed()
        composeTestRule.onNodeWithText("Failed mail").assertIsDisplayed()
        // The queued row shows the "queued" status; the failed row shows the "failed" status.
        composeTestRule.onNodeWithText(string(R.string.outbox_status_queued)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.outbox_status_failed)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.outbox_retry)).assertIsDisplayed()
    }

    @Test
    fun tappingRetry_callsRepositoryRetry() {
        val repo = FakeMailRepository(outbox = listOf(failed("f", "Failed mail", "bob@example.org")))
        setContent(repo)
        waitForText("Failed mail")

        composeTestRule.onNodeWithText(string(R.string.outbox_retry)).performClick()

        composeTestRule.waitUntil(5_000) { repo.retryOutboxCount == 1 }
    }

    @Test
    fun tappingCancel_removesRow_andRecordsCancellation() {
        val repo = FakeMailRepository(
            outbox = listOf(
                queued("q", "Queued mail", "alice@example.org"),
                queued("q2", "Second mail", "carol@example.org"),
            ),
        )
        setContent(repo)
        waitForText("Queued mail")

        // Cancel the first row: the fake removes it from the observed outbox, so the row disappears.
        composeTestRule.onAllNodesWithContentDescription(string(R.string.outbox_cancel))[0].performClick()

        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("Queued mail").fetchSemanticsNodes().isEmpty()
        }
        assertEquals(listOf("q"), repo.canceledOutboxIds)
        composeTestRule.onNodeWithText("Second mail").assertIsDisplayed()
    }
}
