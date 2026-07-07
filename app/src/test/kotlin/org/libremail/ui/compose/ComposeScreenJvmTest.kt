// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.compose

import android.content.Context
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.R
import org.libremail.contacts.ContactSuggestion
import org.libremail.domain.model.Account
import org.libremail.domain.model.AuthType
import org.libremail.domain.model.MailSecurity
import org.libremail.domain.model.OutgoingAttachment
import org.libremail.domain.model.ServerConfig
import org.libremail.ui.theme.LibreMailTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric JVM port of the instrumented [ComposeScreenTest] (batch 7/9 of umbrella #373): drives
 * the email-editor [ComposeScreen] on the JVM via the v2 `createComposeRule()` — no emulator — so its
 * render + interaction code counts toward JaCoCo's JVM-testable surface (this file is dropped from
 * `jacocoNonJvmTestableSurface`). The instrumented [ComposeScreenTest] stays as the on-device E2E.
 *
 * Because [ComposeViewModel] is large (7 collaborators, several `Context`/Room-backed) and its logic
 * is exercised elsewhere, it is **mocked** here (mirroring [org.libremail.ui.accountsetup]'s
 * `AccountPickerScreenJvmTest` / `ManualSetupScreenJvmTest`): the three flows the screen reads
 * (`state`, `accounts`, `finished`) are stubbed so every render/state branch can be injected directly,
 * and interactions are asserted by verifying they forward to the view model. The embedded
 * [RichTextBodyField] (from `RichTextEditor.kt`, itself unit-tested by `RichTextEditorTest`) renders
 * live; the toolbar's accessibility labels and its body-change plumbing are covered here too.
 *
 * A RESUMED [LocalLifecycleOwner] is provided so `collectAsStateWithLifecycle` collects; the same
 * owner backs [LocalOnBackPressedDispatcherOwner] for the screen's `BackHandler`, and a no-op
 * [ActivityResultRegistryOwner] lets the attachment/inline-image `rememberLauncherForActivityResult`
 * register without surfacing a real picker. `@GraphicsMode(NATIVE)` + `@Config(sdk = [36])` match the
 * rest of the suite; the tall `qualifiers` keeps the whole scrolling form on-screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// A tall display so the whole scrolling compose form — From/To/Subject/attachments/body + toolbar —
// fits Robolectric's small default viewport, keeping controls on-screen for the assertions below.
@Config(sdk = [36], qualifiers = "+w411dp-h2000dp")
class ComposeScreenJvmTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun string(resId: Int): String = context.getString(resId)

    private val account = Account(
        id = "imap:me@example.com",
        email = "me@example.com",
        displayName = "Me",
        authType = AuthType.PASSWORD_IMAP,
        imap = ServerConfig("imap.example.com", 993, MailSecurity.SSL_TLS),
        smtp = ServerConfig("smtp.example.com", 465, MailSecurity.SSL_TLS),
    )

    private val otherAccount = Account(
        id = "imap:other@example.com",
        email = "other@example.com",
        displayName = "Other",
        authType = AuthType.PASSWORD_IMAP,
        imap = ServerConfig("imap.example.com", 993, MailSecurity.SSL_TLS),
        smtp = ServerConfig("smtp.example.com", 465, MailSecurity.SSL_TLS),
    )

    /** RESUMED owner (also the back-press dispatcher owner) so state collects and `BackHandler` composes. */
    private val owner = object : OnBackPressedDispatcherOwner {
        private val registry =
            LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        private val dispatcher = OnBackPressedDispatcher()
        override val lifecycle: Lifecycle get() = registry
        override val onBackPressedDispatcher: OnBackPressedDispatcher get() = dispatcher
    }

    /**
     * A no-op registry so the screen's attachment / inline-image `rememberLauncherForActivityResult`
     * can register (and, if tapped, launch) on the JVM without surfacing a real document picker.
     */
    private val noopRegistryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                // Intentionally never dispatch a result: the launch is a no-op in this JVM test.
            }
        }
    }

    private fun viewModel(
        state: ComposeUiState = ComposeUiState(),
        accounts: List<Account> = listOf(account),
        finished: Flow<Unit> = emptyFlow(),
    ): ComposeViewModel {
        val vm = mockk<ComposeViewModel>(relaxed = true)
        every { vm.state } returns MutableStateFlow(state)
        every { vm.accounts } returns MutableStateFlow(accounts)
        every { vm.finished } returns finished
        return vm
    }

    private fun setContent(viewModel: ComposeViewModel, onBack: () -> Unit = {}) {
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalLifecycleOwner provides owner,
                LocalOnBackPressedDispatcherOwner provides owner,
                LocalActivityResultRegistryOwner provides noopRegistryOwner,
            ) {
                LibreMailTheme(darkTheme = false, dynamicColor = false) {
                    ComposeScreen(onBack = onBack, viewModel = viewModel)
                }
            }
        }
    }

    private fun waitForText(text: String) = composeTestRule.waitUntil(TIMEOUT_MS) {
        composeTestRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    /** Matches the editable field labelled [labelRes] but not the collapsed Cc/Bcc link buttons. */
    private fun editableField(labelRes: Int) = composeTestRule.onNode(hasText(string(labelRes)) and hasSetTextAction())

    @Test
    fun initialRender_showsRecipientSubjectBodyAttachAndSingleAccountFrom() {
        setContent(viewModel())

        composeTestRule.onNodeWithText(string(R.string.compose_to)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.compose_subject)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.compose_body)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.compose_attach)).assertIsDisplayed()
        // Exactly one account → the From row shows the address as plain text (no dropdown affordance).
        composeTestRule.onNodeWithText(account.email).assertIsDisplayed()
        // An empty recipient leaves Send disabled.
        composeTestRule.onNodeWithContentDescription(string(R.string.action_send)).assertIsNotEnabled()
    }

    @Test
    fun recipientEntered_enablesSend_andTapInvokesSend() {
        val vm = viewModel(ComposeUiState(to = "you@example.com"))
        setContent(vm)

        composeTestRule.onNodeWithContentDescription(string(R.string.action_send))
            .assertIsEnabled()
            .performClick()

        verify { vm.send() }
    }

    @Test
    fun sendingState_showsSpinner_andDisablesSend() {
        setContent(viewModel(ComposeUiState(to = "you@example.com", sending = true)))

        // The blocking overlay spinner renders...
        composeTestRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
        // ...and Send is disabled while a send is in flight, even with a valid recipient.
        composeTestRule.onNodeWithContentDescription(string(R.string.action_send)).assertIsNotEnabled()
    }

    @Test
    fun tappingBack_invokesOnExit() {
        val vm = viewModel()
        setContent(vm)

        composeTestRule.onNodeWithContentDescription(string(R.string.action_back)).performClick()

        verify { vm.onExit() }
    }

    @Test
    fun typingRecipientSubjectAndBody_forwardsToTheViewModel() {
        val vm = viewModel()
        setContent(vm)

        composeTestRule.onNodeWithText(string(R.string.compose_to)).performTextInput("you@example.com")
        composeTestRule.onNodeWithText(string(R.string.compose_subject)).performTextInput("Hello")
        composeTestRule.onNodeWithText(string(R.string.compose_body)).performTextInput("Body text")

        verify { vm.onToChange(any()) }
        verify { vm.onSubjectChange(any()) }
        verify { vm.onBodyChange(any(), any()) }
    }

    @Test
    fun suggestions_render_andTappingOneForwardsToPickSuggestion() {
        val suggestion = ContactSuggestion(name = "Alice Example", email = "alice@example.org")
        val vm = viewModel(ComposeUiState(to = "al", suggestions = listOf(suggestion)))
        setContent(vm)

        composeTestRule.onNodeWithText("Alice Example").assertIsDisplayed()
        composeTestRule.onNodeWithText("alice@example.org").assertIsDisplayed()

        composeTestRule.onNodeWithText("Alice Example").performClick()

        verify { vm.pickSuggestion(suggestion) }
    }

    @Test
    fun attachmentPrompt_whenFlagged_showsDialog_andYesInvokesAttachInstead() {
        val vm = viewModel(ComposeUiState(to = "you@example.com", showAttachmentPrompt = true))
        setContent(vm)

        composeTestRule.onNodeWithText(string(R.string.confirm_attachment_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.action_yes)).performClick()

        verify { vm.attachInstead() }
    }

    @Test
    fun attachmentPrompt_noInvokesSendAnyway() {
        val vm = viewModel(ComposeUiState(to = "you@example.com", showAttachmentPrompt = true))
        setContent(vm)

        composeTestRule.onNodeWithText(string(R.string.action_no)).performClick()

        verify { vm.sendAnyway() }
    }

    @Test
    fun noAccounts_showsNoAccountLabel() {
        setContent(viewModel(accounts = emptyList()))

        composeTestRule.onNodeWithText(string(R.string.compose_no_account)).assertIsDisplayed()
    }

    @Test
    fun multipleAccounts_showFromDropdown_andSelectingOneForwards() {
        val vm = viewModel(
            state = ComposeUiState(fromAccountId = account.id),
            accounts = listOf(account, otherAccount),
        )
        setContent(vm)

        // With >1 account the From row is a dropdown anchor showing the selected address.
        composeTestRule.onNodeWithText(account.email).assertIsDisplayed().performClick()
        // The opened menu lists the other account; picking it forwards the id to the view model.
        composeTestRule.onNodeWithText(otherAccount.email).performClick()

        verify { vm.selectFrom(otherAccount.id) }
    }

    @Test
    fun attachments_normalRendersChip_inlineHidden_andTappingRemoves() {
        val normal = OutgoingAttachment(uri = "content://doc/1", name = "report.pdf")
        val inline = OutgoingAttachment(
            uri = "content://img/1",
            name = "inline-image.png",
            contentId = "cid-1",
            isInline = true,
        )
        val vm = viewModel(ComposeUiState(attachments = listOf(normal, inline)))
        setContent(vm)

        // Regular attachments show as removable chips; inline images live in the body, not as chips.
        composeTestRule.onNodeWithText("report.pdf").assertIsDisplayed()
        composeTestRule.onNodeWithText("inline-image.png").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(string(R.string.compose_attachment_remove)).assertIsDisplayed()

        composeTestRule.onNodeWithText("report.pdf").performClick()

        verify { vm.removeAttachment("content://doc/1") }
    }

    @Test
    fun ccBcc_startCollapsed_expandViaLink_andForwardEdits() {
        val vm = viewModel()
        setContent(vm)

        // Collapsed: Cc exists only as a link, not as an editable field.
        editableField(R.string.compose_cc).assertDoesNotExist()

        composeTestRule.onNodeWithText(string(R.string.compose_cc)).performClick()
        editableField(R.string.compose_cc).performTextInput("cc@example.com")

        verify { vm.onCcChange(any()) }
    }

    @Test
    fun prefilledCc_startsExpandedWithItsValue() {
        setContent(viewModel(ComposeUiState(cc = "cc@example.com")))

        editableField(R.string.compose_cc).assertIsDisplayed()
        composeTestRule.onNodeWithText("cc@example.com").assertIsDisplayed()
    }

    @Test
    fun formattingToolbar_buttonsCarryOnClickLabelsForAccessibility() {
        setContent(viewModel())

        // Every toolbar button (see FormattingToolbar in RichTextEditor.kt) is a plain clickable Box with
        // a bare glyph Text as its only visible content, so TalkBack relies entirely on the click action's
        // label (there is no separate contentDescription) to announce what the button does.
        val buttons = listOf(
            "B" to R.string.format_bold,
            "I" to R.string.format_italic,
            "U" to R.string.format_underline,
            "•" to R.string.format_bullet_list,
            "1." to R.string.format_numbered_list,
            "❝" to R.string.format_quote,
            "🔗" to R.string.format_link,
        )
        buttons.forEach { (glyph, descriptionRes) ->
            val config = composeTestRule.onNodeWithText(glyph).fetchSemanticsNode().config
            val clickLabel = if (config.contains(SemanticsActions.OnClick)) {
                config[SemanticsActions.OnClick].label
            } else {
                null
            }
            val message = "toolbar button \"$glyph\" is missing its accessibility label"
            assertEquals(message, string(descriptionRes), clickLabel)
        }
    }

    @Test
    fun tappingAToolbarButton_flowsABodyChangeToTheViewModel() {
        val vm = viewModel()
        setContent(vm)

        // The bullet button applies a block marker to the caret's line and re-emits the body — proving a
        // toolbar tap reaches ComposeViewModel.onBodyChange (both plaintext + HTML forms).
        composeTestRule.onNodeWithText("•").performClick()

        verify { vm.onBodyChange(any(), any()) }
    }

    @Test
    fun finishedSignal_invokesOnBack() {
        var backed = false
        setContent(viewModel(finished = flowOf(Unit)), onBack = { backed = true })

        composeTestRule.waitUntil(TIMEOUT_MS) { backed }
        assertTrue(backed)
    }

    @Test
    fun errorState_isSurfacedAsASnackbar() {
        setContent(viewModel(ComposeUiState(error = "Could not send")))

        waitForText("Could not send")
        composeTestRule.onNodeWithText("Could not send").assertIsDisplayed()
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
