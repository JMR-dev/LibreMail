// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.accountsetup

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.R
import org.libremail.ui.FakeAccountRepository
import org.libremail.ui.theme.LibreMailTheme

/**
 * End-to-end UI test for manual IMAP/SMTP account setup. Drives the real [ManualSetupScreen] and a
 * real [ManualSetupViewModel] backed by an in-memory [FakeAccountRepository].
 */
@RunWith(AndroidJUnit4::class)
class ManualSetupScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun string(resId: Int) = composeTestRule.activity.getString(resId)

    // Build the view model once and capture it, so recomposition doesn't recreate it.
    private fun setContent(
        repository: FakeAccountRepository = FakeAccountRepository(),
        onAccountAdded: (String) -> Unit = {},
    ) {
        val viewModel = ManualSetupViewModel(repository)
        composeTestRule.setContent {
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                ManualSetupScreen(onBack = {}, onAccountAdded = onAccountAdded, viewModel = viewModel)
            }
        }
    }

    private fun fillValidForm() {
        composeTestRule.onNodeWithText(string(R.string.manual_email)).performTextInput("user@example.com")
        composeTestRule.onNodeWithText(string(R.string.manual_password)).performTextInput("app-password")
        composeTestRule.onNodeWithText(string(R.string.manual_imap_server)).performTextInput("imap.example.com")
        composeTestRule.onNodeWithText(string(R.string.manual_smtp_server)).performTextInput("smtp.example.com")
    }

    @Test
    fun testAndAddButton_isDisabledUntilRequiredFieldsAreFilled() {
        setContent()
        composeTestRule.onNodeWithText(string(R.string.manual_test_and_add)).assertIsNotEnabled()
        fillValidForm()
        composeTestRule.onNodeWithText(string(R.string.manual_test_and_add)).assertIsEnabled()
    }

    @Test
    fun advancedToggle_revealsPortField() {
        setContent()
        composeTestRule.onNodeWithText(string(R.string.manual_imap_port)).assertDoesNotExist()
        composeTestRule.onNodeWithText(string(R.string.settings_advanced)).performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText(string(R.string.manual_imap_port)).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(string(R.string.manual_imap_port)).assertExists()
    }

    @Test
    fun submit_onSuccess_invokesOnAccountAddedWithEnteredCredentials() {
        val repository = FakeAccountRepository(result = Result.success(listOf("INBOX")))
        var added = false
        setContent(repository) { added = true }

        fillValidForm()
        composeTestRule.onNodeWithText(string(R.string.manual_test_and_add)).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) { added }
        assertTrue(added)
        assertEquals("user@example.com", repository.addedAccount?.email)
        assertEquals("app-password", repository.addedPassword)
    }

    @Test
    fun submit_onFailure_showsErrorAndStaysOnScreen() {
        val repository = FakeAccountRepository(result = Result.failure(RuntimeException("Login failed")))
        var added = false
        setContent(repository) { added = true }

        fillValidForm()
        composeTestRule.onNodeWithText(string(R.string.manual_test_and_add)).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Login failed").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Login failed").assertExists()
        assertFalse(added)
    }
}
