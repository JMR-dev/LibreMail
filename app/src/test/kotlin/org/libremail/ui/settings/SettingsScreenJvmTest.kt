// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.settings

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
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
import org.libremail.contacts.ContactPermissionState
import org.libremail.data.settings.AppSettings
import org.libremail.data.settings.FetchPolicy
import org.libremail.domain.model.Account
import org.libremail.ui.theme.LibreMailTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric JVM Compose tests for [SettingsScreen] + its stateless [ContactAutocompleteRow] (umbrella
 * #373, batch #380). Drives the global settings screen on the JVM under [RobolectricTestRunner] via the
 * v2 `createComposeRule()` — no emulator — with a MockK [SettingsViewModel] (its own logic is covered by
 * `SettingsViewModelTest`) so `SettingsScreen.kt` counts toward JaCoCo's JVM-testable surface: the
 * empty-vs-populated accounts branch, the fetch-policy radios, the appearance/notification/backup
 * switches, the collapsible Advanced section, the battery subtitle branch, the retention wiring, and the
 * contacts row's rationale-vs-blocked dialog branch. The instrumented `SettingsScreenTest` and
 * `ContactAutocompleteRowTest` stay as the on-device E2E. See
 * [org.libremail.ui.onboarding.AddAnotherAccountScreenJvmTest] for the pattern.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class SettingsScreenJvmTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun string(resId: Int): String = context.getString(resId)

    // collectAsStateWithLifecycle / LifecycleEventEffect need a LocalLifecycleOwner; the v2 rule hosts no
    // Activity, so provide a RESUMED one by hand (createUnsafe skips the main-thread assertion on the JVM).
    private val lifecycleOwner: LifecycleOwner = object : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private fun mockViewModel(
        settings: AppSettings = AppSettings(),
        accounts: List<Account> = emptyList(),
        advancedExpanded: Boolean = false,
        appLockMessage: Int? = null,
        batteryUnrestricted: Boolean = false,
        contactsRequested: Boolean = false,
        hasContactsPermission: Boolean = false,
    ): SettingsViewModel {
        val viewModel = mockk<SettingsViewModel>(relaxed = true)
        every { viewModel.settings } returns MutableStateFlow(settings)
        every { viewModel.accounts } returns MutableStateFlow(accounts)
        every { viewModel.advancedExpanded } returns MutableStateFlow(advancedExpanded)
        every { viewModel.appLockMessage } returns MutableStateFlow(appLockMessage)
        every { viewModel.batteryUnrestricted } returns MutableStateFlow(batteryUnrestricted)
        every { viewModel.contactsPermissionRequested } returns MutableStateFlow(contactsRequested)
        every { viewModel.hasContactsPermission() } returns hasContactsPermission
        return viewModel
    }

    private fun setContent(
        viewModel: SettingsViewModel,
        onAddAccount: () -> Unit = {},
        onOpenAccount: (String) -> Unit = {},
        onSelectTab: (org.libremail.ui.TopDest) -> Unit = {},
        onReportProblem: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                LibreMailTheme(darkTheme = false, dynamicColor = false) {
                    SettingsScreen(
                        onAddAccount = onAddAccount,
                        onOpenAccount = onOpenAccount,
                        onSelectTab = onSelectTab,
                        onReportProblem = onReportProblem,
                        viewModel = viewModel,
                    )
                }
            }
        }
    }

    private fun setRow(content: @Composable () -> Unit) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                LibreMailTheme(darkTheme = false, dynamicColor = false) { content() }
            }
        }
    }

    @Test
    fun emptyAccounts_showsTitleAndNoAccountsMessage() {
        setContent(mockViewModel(accounts = emptyList()))

        // "Settings" also labels the bottom-nav tab, so match the (app-bar) title among all its nodes.
        composeTestRule.onAllNodesWithText(string(R.string.title_settings)).onFirst().assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.settings_accounts)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.settings_no_accounts)).assertIsDisplayed()
    }

    @Test
    fun withAccounts_rendersAccountRow_andNoEmptyMessage() {
        setContent(mockViewModel(accounts = listOf(Account.outlook("me@example.com"))))

        composeTestRule.onNodeWithText("me@example.com").assertIsDisplayed()
        composeTestRule.onAllNodesWithText(string(R.string.settings_no_accounts)).assertCountEquals(0)
    }

    @Test
    fun tappingAddAccountRow_invokesCallback() {
        var added = false
        setContent(mockViewModel(), onAddAccount = { added = true })

        composeTestRule.onNodeWithText(string(R.string.settings_add_account)).performScrollTo().performClick()
        assertTrue(added)
    }

    @Test
    fun selectingOnDemandFetchPolicy_callsViewModel() {
        val viewModel = mockViewModel(settings = AppSettings(fetchPolicy = FetchPolicy.ALWAYS))
        setContent(viewModel)

        composeTestRule.onNodeWithText(string(R.string.fetch_on_demand)).performScrollTo().performClick()

        verify { viewModel.setFetchPolicy(FetchPolicy.ON_DEMAND) }
    }

    @Test
    fun togglingDynamicColor_callsViewModel() {
        val viewModel = mockViewModel(settings = AppSettings(dynamicColor = false))
        setContent(viewModel)

        composeTestRule.onNodeWithText(string(R.string.settings_dynamic_color)).performScrollTo().performClick()

        verify { viewModel.setDynamicColor(true) }
    }

    @Test
    fun togglingNewMailNotifications_callsViewModel() {
        val viewModel = mockViewModel(settings = AppSettings(newMailNotifications = true))
        setContent(viewModel)

        composeTestRule.onNodeWithText(string(R.string.settings_new_mail)).performScrollTo().performClick()

        verify { viewModel.setNewMailNotifications(false) }
    }

    @Test
    fun batteryRow_showsOptimizedSubtitle_whenRestricted() {
        setContent(mockViewModel(batteryUnrestricted = false))

        composeTestRule.onNodeWithText(string(R.string.settings_adv_battery_optimized))
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun batteryRow_showsUnrestrictedSubtitle_whenUnrestricted() {
        setContent(mockViewModel(batteryUnrestricted = true))

        composeTestRule.onNodeWithText(string(R.string.settings_adv_battery_unrestricted))
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun selectingGlobalRetentionCount_callsViewModel() {
        val viewModel = mockViewModel(settings = AppSettings(retentionCount = 0))
        setContent(viewModel)

        composeTestRule.onNodeWithText(string(R.string.retention_count_500)).performScrollTo().performClick()

        // The global screen maps a chosen count straight through (null coalesced to 0, but 500 here).
        verify { viewModel.setRetentionCount(500) }
    }

    @Test
    fun tappingReportProblemRow_invokesCallback() {
        var reported = false
        setContent(mockViewModel(), onReportProblem = { reported = true })

        composeTestRule.onNodeWithText(string(R.string.settings_report_problem)).performScrollTo().performClick()
        assertTrue(reported)
    }

    @Test
    fun tappingAdvancedHeader_callsToggleAdvanced() {
        val viewModel = mockViewModel(advancedExpanded = false)
        setContent(viewModel)

        composeTestRule.onNodeWithText(string(R.string.settings_advanced)).performScrollTo().performClick()

        verify { viewModel.toggleAdvanced() }
    }

    @Test
    fun advancedExpanded_showsAdvancedSwitches_andTogglingOneCallsViewModel() {
        val viewModel = mockViewModel(
            advancedExpanded = true,
            settings = AppSettings(pushIdle = true, allowStartTls = false),
        )
        setContent(viewModel)

        composeTestRule.onNodeWithText(string(R.string.settings_adv_app_lock)).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.settings_adv_encrypt_cache))
            .performScrollTo().assertIsDisplayed()

        composeTestRule.onNodeWithText(string(R.string.settings_adv_starttls)).performScrollTo().performClick()
        verify { viewModel.setAllowStartTls(true) }
    }

    @Test
    fun contactsRow_deniedState_showsOffSubtitle_andTapOpensRationaleDialog() {
        setContent(mockViewModel(contactsRequested = false, hasContactsPermission = false))

        composeTestRule.onNodeWithText(string(R.string.settings_contacts_autocomplete_off))
            .performScrollTo().assertIsDisplayed()

        composeTestRule.onNodeWithText(string(R.string.settings_contacts_autocomplete)).performScrollTo().performClick()

        // DENIED (never asked, re-requestable) opens the rationale dialog before the system prompt. Assert
        // on the unique rationale body — the dialog *title* reuses the row's own "Recipient autocomplete".
        composeTestRule.onNodeWithText(string(R.string.settings_contacts_rationale)).assertIsDisplayed()

        // Cancelling dismisses the dialog.
        composeTestRule.onNodeWithText(string(R.string.cancel)).performClick()
        composeTestRule.onAllNodesWithText(string(R.string.settings_contacts_rationale)).assertCountEquals(0)
    }

    @Test
    fun contactsRow_blockedState_showsBlockedSubtitle_andTapOpensBlockedDialog() {
        // granted = false, no activity (so no rationale), already requested → permanently blocked.
        setContent(mockViewModel(contactsRequested = true, hasContactsPermission = false))

        composeTestRule.onNodeWithText(string(R.string.settings_contacts_autocomplete_blocked))
            .performScrollTo().assertIsDisplayed()

        composeTestRule.onNodeWithText(string(R.string.settings_contacts_autocomplete)).performScrollTo().performClick()

        composeTestRule.onNodeWithText(string(R.string.settings_contacts_blocked_body)).assertIsDisplayed()
    }

    // --- ContactAutocompleteRow (stateless, lives in SettingsScreen.kt) — mirrors ContactAutocompleteRowTest ---

    @Test
    fun contactAutocompleteRow_granted_showsOnSubtitle_andIsClickable() {
        var clicked = false
        setRow { ContactAutocompleteRow(state = ContactPermissionState.GRANTED, onClick = { clicked = true }) }

        composeTestRule.onNodeWithText(string(R.string.settings_contacts_autocomplete)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.settings_contacts_autocomplete_on)).assertIsDisplayed()

        composeTestRule.onNodeWithText(string(R.string.settings_contacts_autocomplete_on)).performClick()
        assertTrue(clicked)
    }

    @Test
    fun contactAutocompleteRow_denied_showsOffSubtitle() {
        setRow { ContactAutocompleteRow(state = ContactPermissionState.DENIED, onClick = {}) }

        composeTestRule.onNodeWithText(string(R.string.settings_contacts_autocomplete_off)).assertIsDisplayed()
    }

    @Test
    fun contactAutocompleteRow_blocked_showsBlockedSubtitle() {
        setRow { ContactAutocompleteRow(state = ContactPermissionState.BLOCKED, onClick = {}) }

        composeTestRule.onNodeWithText(string(R.string.settings_contacts_autocomplete_blocked)).assertIsDisplayed()
    }
}
