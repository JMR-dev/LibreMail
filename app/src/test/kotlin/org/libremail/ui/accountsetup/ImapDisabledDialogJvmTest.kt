// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.accountsetup

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.R
import org.libremail.ui.theme.LibreMailTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric JVM Compose test for the reactive "IMAP is disabled" dialog (#390). Drives the real
 * [ImapDisabledDialog] on the JVM via the v2 `createComposeRule()` under [RobolectricTestRunner] — no
 * emulator — so the dialog counts toward JaCoCo's JVM-testable surface. A recording [UriHandler]
 * captures the help-link launch instead of opening a real browser; the instrumented
 * [org.libremail.ui.accountsetup.AppPasswordSetupScreenTest] covers the end-to-end error state on
 * device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "+w411dp-h2000dp")
class ImapDisabledDialogJvmTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun string(resId: Int, vararg args: Any): String = context.getString(resId, *args)

    private val openedUrls = mutableListOf<String>()
    private val recordingUriHandler = object : UriHandler {
        override fun openUri(uri: String) {
            openedUrls.add(uri)
        }
    }

    private val outlookPrompt = ImapDisabledPrompt(brand = "Outlook", helpUrl = "https://support.microsoft.com/imap")

    private fun setContent(prompt: ImapDisabledPrompt, onDismiss: () -> Unit = {}) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalUriHandler provides recordingUriHandler) {
                LibreMailTheme(darkTheme = false, dynamicColor = false) {
                    ImapDisabledDialog(prompt = prompt, onDismiss = onDismiss)
                }
            }
        }
    }

    @Test
    fun brandedPrompt_showsTitle_brandMessage_help_andDismiss() {
        setContent(outlookPrompt)

        composeTestRule.onNodeWithText(string(R.string.imap_disabled_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.imap_disabled_message, "Outlook")).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.imap_disabled_help)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.imap_disabled_dismiss)).assertIsDisplayed()
    }

    @Test
    fun genericPrompt_showsGenericMessage_andNoHelpLink() {
        setContent(ImapDisabledPrompt(brand = null, helpUrl = null))

        composeTestRule.onNodeWithText(string(R.string.imap_disabled_message_generic)).assertIsDisplayed()
        // No provider page to link, so the help button is absent — only "Got it" remains.
        composeTestRule.onNodeWithText(string(R.string.imap_disabled_help)).assertDoesNotExist()
        composeTestRule.onNodeWithText(string(R.string.imap_disabled_dismiss)).assertIsDisplayed()
    }

    @Test
    fun tappingHelp_opensTheProviderPage_withoutDismissing() {
        var dismissed = false
        setContent(outlookPrompt, onDismiss = { dismissed = true })

        composeTestRule.onNodeWithText(string(R.string.imap_disabled_help)).performClick()

        assertEquals(listOf("https://support.microsoft.com/imap"), openedUrls)
        // Opening the link leaves the dialog up so it is still there when the user returns.
        assertFalse("Opening the help link must not dismiss the dialog", dismissed)
    }

    @Test
    fun tappingGotIt_dismisses() {
        var dismissed = false
        setContent(outlookPrompt, onDismiss = { dismissed = true })

        composeTestRule.onNodeWithText(string(R.string.imap_disabled_dismiss)).performClick()

        assertTrue(dismissed)
    }
}
