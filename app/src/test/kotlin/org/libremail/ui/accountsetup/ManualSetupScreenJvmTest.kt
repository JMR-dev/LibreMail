// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.accountsetup

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.R
import org.libremail.domain.model.MailSecurity
import org.libremail.ui.theme.LibreMailTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric JVM Compose test (#378, umbrella #373) for manual IMAP/SMTP account setup. Mirrors the
 * instrumented [ManualSetupScreenTest] on the JVM via the v2 `createComposeRule()` under
 * [RobolectricTestRunner] — no emulator — so [ManualSetupScreen] counts toward JaCoCo's JVM-testable
 * surface (this file is dropped from `jacocoNonJvmTestableSurface`). The instrumented test stays as
 * the on-device E2E.
 *
 * [ManualSetupViewModel] is mocked (its own logic is covered by [ManualSetupViewModelTest]); this
 * exercises the screen's field/submit render, the advanced disclosure (ports + the security selector
 * that omits the cleartext option), and the enabled/busy/error/done branches.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// A tall display so the whole scrolling setup form — including the expanded advanced section — fits
// Robolectric's small default viewport, keeping every control on-screen for the assertions below.
@Config(sdk = [36], qualifiers = "+w411dp-h2000dp")
class ManualSetupScreenJvmTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun string(resId: Int): String = context.getString(resId)

    /** RESUMED owner so `collectAsStateWithLifecycle` collects the form state. */
    private val resumedOwner = object : LifecycleOwner {
        private val registry =
            LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private fun viewModel(form: ManualSetupForm = ManualSetupForm()): ManualSetupViewModel {
        val vm = mockk<ManualSetupViewModel>(relaxed = true)
        every { vm.form } returns MutableStateFlow(form)
        return vm
    }

    private fun setContent(viewModel: ManualSetupViewModel, onAccountAdded: (String) -> Unit = {}) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides resumedOwner) {
                LibreMailTheme(darkTheme = false, dynamicColor = false) {
                    ManualSetupScreen(onBack = {}, onAccountAdded = onAccountAdded, viewModel = viewModel)
                }
            }
        }
    }

    @Test
    fun rendersCredentialAndServerFields_withDisabledSubmit() {
        setContent(viewModel())

        composeTestRule.onNodeWithText(string(R.string.manual_email)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.manual_password)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.manual_incoming)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.manual_imap_server)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.manual_outgoing)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.manual_smtp_server)).assertIsDisplayed()
        // An empty form is invalid, so the submit button starts disabled.
        composeTestRule.onNodeWithText(string(R.string.manual_test_and_add)).assertIsNotEnabled()
    }

    @Test
    fun tappingBack_invokesOnBack() {
        var backed = false
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides resumedOwner) {
                LibreMailTheme(darkTheme = false, dynamicColor = false) {
                    ManualSetupScreen(onBack = { backed = true }, onAccountAdded = {}, viewModel = viewModel())
                }
            }
        }

        composeTestRule.onNodeWithContentDescription(string(R.string.action_back)).performClick()

        assertTrue(backed)
    }

    @Test
    fun typingCredentialsAndServers_forwardsToTheViewModel() {
        val vm = viewModel()
        setContent(vm)

        composeTestRule.onNodeWithText(string(R.string.manual_email)).performTextInput("user@example.com")
        composeTestRule.onNodeWithText(string(R.string.manual_password)).performTextInput("secret")
        composeTestRule.onNodeWithText(string(R.string.manual_imap_server)).performTextInput("imap.example.com")
        composeTestRule.onNodeWithText(string(R.string.manual_smtp_server)).performTextInput("smtp.example.com")

        verify { vm.onEmail(any()) }
        verify { vm.onPassword(any()) }
        verify { vm.onImapHost(any()) }
        verify { vm.onSmtpHost(any()) }
    }

    @Test
    fun validForm_enablesSubmit_andTapCallsTestAndSave() {
        val vm = viewModel(
            ManualSetupForm(
                email = "user@example.com",
                password = "secret",
                imapHost = "imap.example.com",
                smtpHost = "smtp.example.com",
            ),
        )
        setContent(vm)

        composeTestRule.onNodeWithText(string(R.string.manual_test_and_add)).assertIsEnabled().performClick()

        verify { vm.testAndSave() }
    }

    @Test
    fun connectingStatus_disablesSubmit() {
        setContent(
            viewModel(
                ManualSetupForm(
                    email = "user@example.com",
                    password = "secret",
                    imapHost = "imap.example.com",
                    smtpHost = "smtp.example.com",
                    status = SetupStatus.CONNECTING,
                ),
            ),
        )

        composeTestRule.onNodeWithText(string(R.string.manual_test_and_add)).assertIsNotEnabled()
    }

    @Test
    fun doneStatus_reportsTheNewAccountIdToOnAccountAdded() {
        var addedId: String? = null
        setContent(
            viewModel(ManualSetupForm(status = SetupStatus.DONE, addedAccountId = "imap:user@example.com")),
            onAccountAdded = { addedId = it },
        )

        composeTestRule.waitUntil(5_000) { addedId != null }
        assertEquals("imap:user@example.com", addedId)
    }

    @Test
    fun anError_isSurfacedAsASnackbar() {
        setContent(viewModel(ManualSetupForm(error = "Login failed")))

        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("Login failed").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Login failed").assertIsDisplayed()
    }

    @Test
    fun expandedAdvanced_revealsPortsAndSecurity_andOmitsTheCleartextOption() {
        setContent(viewModel(ManualSetupForm(advancedExpanded = true)))

        composeTestRule.onNodeWithText(string(R.string.manual_imap_port)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.manual_imap_security)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.manual_smtp_port)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.manual_smtp_security)).assertIsDisplayed()
        // Each selector offers only the two encrypted modes...
        composeTestRule.onAllNodesWithText("SSL/TLS")[0].assertIsDisplayed()
        composeTestRule.onAllNodesWithText("STARTTLS")[0].assertIsDisplayed()
        // ...never the cleartext (NONE) option.
        composeTestRule.onNodeWithText("None").assertDoesNotExist()
    }

    @Test
    fun advancedToggleAndSecurityChips_forwardToTheViewModel() {
        val vm = viewModel(ManualSetupForm(advancedExpanded = true))
        setContent(vm)

        composeTestRule.onNodeWithText(string(R.string.settings_advanced)).performClick()
        verify { vm.toggleAdvanced() }

        // Two selectors -> the IMAP one renders first, the SMTP one second.
        composeTestRule.onAllNodesWithText("STARTTLS")[0].performClick()
        verify { vm.onImapSecurity(MailSecurity.STARTTLS) }
        composeTestRule.onAllNodesWithText("STARTTLS")[1].performClick()
        verify { vm.onSmtpSecurity(MailSecurity.STARTTLS) }
    }
}
