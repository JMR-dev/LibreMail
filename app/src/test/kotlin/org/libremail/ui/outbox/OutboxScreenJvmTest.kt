// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.outbox

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
import org.libremail.domain.model.OutboxMessage
import org.libremail.domain.repository.MailRepository
import org.libremail.ui.theme.LibreMailTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric JVM port of the instrumented [OutboxScreenTest] (batch 4/9 of umbrella #373): drives
 * [OutboxScreen] + the real [OutboxViewModel] over a mocked [MailRepository] on the JVM via the v2
 * `createComposeRule()` — no emulator — so [OutboxScreen]'s render + interaction code counts toward
 * JaCoCo's JVM-testable surface. Covers the empty state (no retry action), the queued-vs-failed row
 * rendering, retry, and cancel (row disappears + delegates to the repository). The instrumented
 * [OutboxScreenTest] stays as the on-device E2E. See
 * [org.libremail.ui.onboarding.AddAnotherAccountScreenJvmTest] for the pattern.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class OutboxScreenJvmTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun string(resId: Int) = context.getString(resId)

    private fun queued(id: String, subject: String, to: String) =
        OutboxMessage(id = id, to = to, subject = subject, body = "body", createdAt = 1_000L, lastError = null)

    private fun failed(id: String, subject: String, to: String) =
        OutboxMessage(id = id, to = to, subject = subject, body = "body", createdAt = 2_000L, lastError = "SMTP 550")

    /**
     * Builds a relaxed [MailRepository] whose observed outbox is backed by a [MutableStateFlow], so a
     * `cancelOutboxMessage` mutates the observed list and the row disappears (mirroring the DB-backed
     * repository's reactivity), and constructs the real [OutboxViewModel] over it.
     */
    private fun setContent(messages: List<OutboxMessage>): MailRepository {
        val outboxFlow = MutableStateFlow(messages)
        val repo = mockk<MailRepository>(relaxed = true)
        every { repo.observeOutbox() } returns outboxFlow
        coEvery { repo.cancelOutboxMessage(any()) } answers {
            val id = firstArg<String>()
            outboxFlow.value = outboxFlow.value.filterNot { it.id == id }
        }
        val viewModel = OutboxViewModel(repo)
        composeTestRule.setContent {
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                OutboxScreen(onBack = {}, viewModel = viewModel)
            }
        }
        return repo
    }

    private fun waitForText(text: String) = composeTestRule.waitUntil(TIMEOUT_MS) {
        composeTestRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun emptyOutbox_showsEmptyState_andNoRetryAction() {
        setContent(emptyList())

        composeTestRule.onNodeWithText(string(R.string.outbox_empty)).assertIsDisplayed()
        // Retry only appears when there is something to retry.
        composeTestRule.onNodeWithText(string(R.string.outbox_retry)).assertDoesNotExist()
    }

    @Test
    fun populatedOutbox_showsQueuedAndFailedRows_andRetryAction() {
        setContent(
            listOf(
                queued("q", "Queued mail", "alice@example.org"),
                failed("f", "Failed mail", "bob@example.org"),
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
        var retryCount = 0
        val repo = setContent(listOf(failed("f", "Failed mail", "bob@example.org")))
        coEvery { repo.retryOutbox() } answers { retryCount++ }
        waitForText("Failed mail")

        composeTestRule.onNodeWithText(string(R.string.outbox_retry)).performClick()

        composeTestRule.waitUntil(TIMEOUT_MS) { retryCount == 1 }
    }

    @Test
    fun tappingCancel_removesRow_andRecordsCancellation() {
        val repo = setContent(
            listOf(
                queued("q", "Queued mail", "alice@example.org"),
                queued("q2", "Second mail", "carol@example.org"),
            ),
        )
        waitForText("Queued mail")

        // Cancel the first row: the fake removes it from the observed outbox, so the row disappears.
        composeTestRule.onAllNodesWithContentDescription(string(R.string.outbox_cancel))[0].performClick()

        composeTestRule.waitUntil(TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText("Queued mail").fetchSemanticsNodes().isEmpty()
        }
        coVerify(exactly = 1) { repo.cancelOutboxMessage("q") }
        composeTestRule.onNodeWithText("Second mail").assertIsDisplayed()
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
