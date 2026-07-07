// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.settings

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.R
import org.libremail.domain.model.Account
import org.libremail.domain.model.AccountSettings
import org.libremail.ui.theme.LibreMailTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric JVM Compose tests for [AccountSettingsScreen] (umbrella #373, batch #380). Drives the
 * per-account settings screen on the JVM under [RobolectricTestRunner] via the v2 `createComposeRule()`
 * — no emulator — with a MockK [AccountSettingsViewModel] (its own logic is covered by
 * `AccountSettingsViewModelTest`) so `AccountSettingsScreen.kt` counts toward JaCoCo's JVM-testable
 * surface: the email-vs-fallback title branch, the default-account/signature/notification switches, the
 * signatures-summary count branch, the per-account retention (with "use default"), and the remove-account
 * row. The instrumented `AccountSettingsScreenTest` stays as the on-device E2E. See
 * [org.libremail.ui.onboarding.AddAnotherAccountScreenJvmTest] for the pattern.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class AccountSettingsScreenJvmTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val accountId = "imap:me@example.com"

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun string(resId: Int): String = context.getString(resId)

    // collectAsStateWithLifecycle needs a LocalLifecycleOwner; the v2 rule hosts no Activity.
    private val lifecycleOwner: LifecycleOwner = object : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private fun mockViewModel(
        account: Account? = Account.outlook("me@example.com"),
        settings: AccountSettings = AccountSettings(accountId),
        signatureCount: Int = 0,
        defaultSignatureName: String = "",
        isDefaultAccount: Boolean = false,
        removing: Boolean = false,
    ): AccountSettingsViewModel {
        val viewModel = mockk<AccountSettingsViewModel>(relaxed = true)
        every { viewModel.account } returns MutableStateFlow(account)
        every { viewModel.settings } returns MutableStateFlow(settings)
        every { viewModel.signatureCount } returns MutableStateFlow(signatureCount)
        every { viewModel.defaultSignatureName } returns MutableStateFlow(defaultSignatureName)
        every { viewModel.isDefaultAccount } returns MutableStateFlow(isDefaultAccount)
        every { viewModel.removing } returns MutableStateFlow(removing)
        return viewModel
    }

    private fun setContent(
        viewModel: AccountSettingsViewModel,
        onBack: () -> Unit = {},
        onManageSignatures: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                LibreMailTheme(darkTheme = false, dynamicColor = false) {
                    AccountSettingsScreen(
                        onBack = onBack,
                        onManageSignatures = onManageSignatures,
                        viewModel = viewModel,
                    )
                }
            }
        }
    }

    @Test
    fun nullAccount_showsFallbackTitle() {
        setContent(mockViewModel(account = null))

        composeTestRule.onNodeWithText(string(R.string.settings_account_title)).assertIsDisplayed()
    }

    @Test
    fun presentAccount_showsEmailAsTitle() {
        setContent(mockViewModel(account = Account.outlook("me@example.com")))

        composeTestRule.onNodeWithText("me@example.com").assertIsDisplayed()
    }

    @Test
    fun noSignatures_showsNoneSummary() {
        setContent(mockViewModel(signatureCount = 0))

        composeTestRule.onNodeWithText(string(R.string.settings_signatures_summary_none))
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun withSignatures_showsManageRow() {
        setContent(mockViewModel(signatureCount = 2, defaultSignatureName = "Work"))

        composeTestRule.onNodeWithText(string(R.string.settings_signatures_manage))
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun tappingManageSignatures_invokesCallback() {
        var managed = false
        setContent(
            mockViewModel(signatureCount = 1, defaultSignatureName = "Work"),
            onManageSignatures = { managed = true },
        )

        composeTestRule.onNodeWithText(string(R.string.settings_signatures_manage)).performScrollTo().performClick()
        assertTrue(managed)
    }

    @Test
    fun togglingSignatureEnabled_callsViewModel() {
        val viewModel = mockViewModel(settings = AccountSettings(accountId, signatureEnabled = true))
        setContent(viewModel)

        composeTestRule.onNodeWithText(string(R.string.settings_signature_enable)).performScrollTo().performClick()

        verify { viewModel.setSignatureEnabled(false) }
    }

    @Test
    fun togglingAccountNotifications_callsViewModel() {
        val viewModel = mockViewModel(settings = AccountSettings(accountId, notificationsEnabled = true))
        setContent(viewModel)

        composeTestRule.onNodeWithText(string(R.string.settings_account_new_mail)).performScrollTo().performClick()

        verify { viewModel.setNotificationsEnabled(false) }
    }

    @Test
    fun togglingSetDefaultAccount_callsViewModel() {
        val viewModel = mockViewModel(isDefaultAccount = false)
        setContent(viewModel)

        composeTestRule.onNodeWithText(string(R.string.settings_account_set_default)).performScrollTo().performClick()

        verify { viewModel.setDefaultAccount(true) }
    }

    @Test
    fun perAccountRetention_offersUseDefault() {
        setContent(mockViewModel(settings = AccountSettings(accountId, retentionCount = null)))

        // includeUseDefault = true on the per-account screen, so "use the global default" is offered in
        // both the message-count group and the age group.
        composeTestRule.onAllNodesWithText(string(R.string.retention_use_default)).assertCountEquals(2)
    }

    @Test
    fun tappingRemoveAccount_callsViewModel() {
        val viewModel = mockViewModel(removing = false)
        setContent(viewModel)

        composeTestRule.onNodeWithText(string(R.string.account_remove)).performScrollTo().performClick()

        verify { viewModel.removeAccount(any()) }
    }

    @Test
    fun tappingBack_invokesCallback() {
        var backInvoked = false
        setContent(mockViewModel(), onBack = { backInvoked = true })

        composeTestRule.onNodeWithContentDescription(string(R.string.action_back)).performClick()
        assertTrue(backInvoked)
    }
}
