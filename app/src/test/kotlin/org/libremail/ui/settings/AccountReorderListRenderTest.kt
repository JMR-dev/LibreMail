// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.R
import org.libremail.domain.model.Account
import org.libremail.domain.model.AuthType
import org.libremail.domain.model.MailSecurity
import org.libremail.domain.model.ServerConfig
import org.libremail.ui.theme.LibreMailTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertTrue

/**
 * Renders the Settings account list ([AccountReorderList]) on the JVM under Robolectric to prove the
 * per-account auth-error indicator (issue #362): an errored account row shows the "remove and re-add"
 * message beneath its address, while a healthy account shows none.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class AccountReorderListRenderTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val message: String
        get() = RuntimeEnvironment.getApplication().getString(R.string.account_auth_error_remove_readd)

    private fun account(authError: String?) = Account(
        id = "acct",
        email = "a@example.org",
        displayName = "A",
        authType = AuthType.PASSWORD_IMAP,
        imap = ServerConfig("imap.example.org", 993, MailSecurity.SSL_TLS),
        smtp = ServerConfig("smtp.example.org", 465, MailSecurity.SSL_TLS),
        authError = authError,
    )

    @Test
    fun erroredAccountRow_showsTheAuthErrorMessage() {
        composeTestRule.setContent {
            LibreMailTheme {
                AccountReorderList(accounts = listOf(account(message)), onOpenAccount = {}, onReorder = {})
            }
        }

        composeTestRule.onNodeWithText("a@example.org").assertIsDisplayed()
        composeTestRule.onNodeWithText(message).assertIsDisplayed()
    }

    @Test
    fun healthyAccountRow_showsNoAuthError() {
        composeTestRule.setContent {
            LibreMailTheme {
                AccountReorderList(accounts = listOf(account(null)), onOpenAccount = {}, onReorder = {})
            }
        }

        assertTrue(
            composeTestRule.onAllNodesWithText(message).fetchSemanticsNodes().isEmpty(),
            "a healthy account row shows no auth-error message",
        )
    }
}
