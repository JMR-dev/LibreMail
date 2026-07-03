// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.libremail.R
import org.libremail.ui.accountsetup.AccountPickerScreen
import org.libremail.ui.accountsetup.AppPasswordSetupScreen
import org.libremail.ui.accountsetup.ManualSetupScreen
import org.libremail.ui.compose.ComposePrefill
import org.libremail.ui.compose.ComposeScreen
import org.libremail.ui.drafts.DraftsScreen
import org.libremail.ui.mailbox.MailboxScreen
import org.libremail.ui.navigation.Routes
import org.libremail.ui.onboarding.AddAnotherAccountScreen
import org.libremail.ui.onboarding.BatteryOptimizationScreen
import org.libremail.ui.onboarding.ContactsAccessScreen
import org.libremail.ui.onboarding.LicenseScreen
import org.libremail.ui.onboarding.OnboardingViewModel
import org.libremail.ui.onboarding.OnboardingWelcomeScreen
import org.libremail.ui.outbox.OutboxScreen
import org.libremail.ui.reader.ReaderScreen
import org.libremail.ui.reporting.ProblemReportsScreen
import org.libremail.ui.reporting.ReportReviewScreen
import org.libremail.ui.reporting.StartupReportViewModel
import org.libremail.ui.settings.AccountSettingsScreen
import org.libremail.ui.settings.SettingsScreen
import org.libremail.ui.settings.SignatureEditScreen
import org.libremail.ui.settings.SignaturesScreen

@Composable
fun LibreMailApp(
    appViewModel: AppViewModel = hiltViewModel(),
    startupViewModel: StartupReportViewModel = hiltViewModel(),
    pendingCompose: ComposePrefill? = null,
    onComposeHandled: () -> Unit = {},
    pendingOpenMessageId: String? = null,
    onOpenMessageHandled: () -> Unit = {},
) {
    val startDestination by appViewModel.startDestination.collectAsStateWithLifecycle()
    val licenseAccepted by appViewModel.licenseAccepted.collectAsStateWithLifecycle()
    // Hold (render nothing) until the account count AND the license-acceptance flag are known, so a
    // cold start never flashes the wrong screen before onboarding-vs-mailbox — and, within onboarding,
    // license-vs-welcome (#172) — is decided.
    val start = startDestination ?: return
    val licenseAlreadyAccepted = licenseAccepted ?: return
    val navController = rememberNavController()
    val pendingCrash by startupViewModel.pendingCrash.collectAsStateWithLifecycle()

    // A mailto:/share intent opens compose on top of the mailbox, pre-filled. Keyed on the request so
    // it fires once per intent (and again for a new intent delivered while the app is alive).
    LaunchedEffect(pendingCompose) {
        val prefill = pendingCompose ?: return@LaunchedEffect
        navController.navigate(
            Routes.compose(
                to = prefill.to,
                subject = prefill.subject,
                cc = prefill.cc,
                bcc = prefill.bcc,
                body = prefill.body,
            ),
        )
        onComposeHandled()
    }

    // A tapped new-mail notification opens that message's reader on top of the current stack, so back
    // lands where the user was (the mailbox on a cold start). If the account vanished in the meantime
    // (start = onboarding) the request is consumed without navigating.
    LaunchedEffect(pendingOpenMessageId) {
        val messageId = pendingOpenMessageId ?: return@LaunchedEffect
        if (start != Routes.ONBOARDING) navController.navigate(Routes.reader(messageId))
        onOpenMessageHandled()
    }

    NavHost(
        navController = navController,
        startDestination = start,
    ) {
        onboardingGraph(navController, licenseAlreadyAccepted)

        composable(
            route = Routes.MAILBOX_PATTERN,
            arguments = listOf(
                navArgument(Routes.MAILBOX_ARG_ACCOUNT) {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) {
            MailboxScreen(
                onOpenMessage = { id -> navController.navigate(Routes.reader(id)) },
                onCompose = { navController.navigate(Routes.compose()) },
                onOpenDrafts = { navController.navigate(Routes.DRAFTS) },
                onOpenOutbox = { navController.navigate(Routes.OUTBOX) },
                onAddAccount = { navController.navigate(Routes.ACCOUNT_SETUP) },
                onOpenCompose = { draftId -> navController.navigate(Routes.composeDraft(draftId)) },
                onSelectTab = navController::navigateTab,
            )
        }
        composable(
            route = Routes.READER_PATTERN,
            arguments = listOf(navArgument(Routes.READER_ARG_ID) { type = NavType.StringType }),
        ) {
            ReaderScreen(
                onBack = navController::popBackStack,
                onReply = { to, subject, from -> navController.navigate(Routes.compose(to, subject, from)) },
            )
        }
        composable(
            route = Routes.COMPOSE_PATTERN,
            arguments = listOf(
                navArgument(Routes.COMPOSE_ARG_TO) {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument(Routes.COMPOSE_ARG_CC) {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument(Routes.COMPOSE_ARG_BCC) {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument(Routes.COMPOSE_ARG_SUBJECT) {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument(Routes.COMPOSE_ARG_BODY) {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument(Routes.COMPOSE_ARG_FROM) {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument(Routes.COMPOSE_ARG_DRAFT) {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) {
            ComposeScreen(onBack = navController::popBackStack)
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onAddAccount = { navController.navigate(Routes.ACCOUNT_SETUP) },
                onOpenAccount = { accountId -> navController.navigate(Routes.accountSettings(accountId)) },
                onSelectTab = navController::navigateTab,
                onReportProblem = { navController.navigate(Routes.PROBLEM_REPORTS) },
            )
        }
        composable(Routes.PROBLEM_REPORTS) {
            ProblemReportsScreen(
                onBack = navController::popBackStack,
                onOpenReport = { reportId -> navController.navigate(Routes.reportReview(reportId)) },
            )
        }
        composable(
            route = Routes.REPORT_REVIEW_PATTERN,
            arguments = listOf(navArgument(Routes.REPORT_REVIEW_ARG_ID) { type = NavType.StringType }),
        ) {
            ReportReviewScreen(onDone = navController::popBackStack)
        }
        composable(
            route = Routes.ACCOUNT_SETTINGS_PATTERN,
            arguments = listOf(navArgument(Routes.ACCOUNT_SETTINGS_ARG_ID) { type = NavType.StringType }),
        ) { backStackEntry ->
            val accountId = backStackEntry.arguments?.getString(Routes.ACCOUNT_SETTINGS_ARG_ID).orEmpty()
            AccountSettingsScreen(
                onBack = navController::popBackStack,
                onManageSignatures = { navController.navigate(Routes.signatures(accountId)) },
            )
        }
        composable(
            route = Routes.SIGNATURES_PATTERN,
            arguments = listOf(navArgument(Routes.SIGNATURES_ARG_ACCOUNT) { type = NavType.StringType }),
        ) { backStackEntry ->
            val accountId = backStackEntry.arguments?.getString(Routes.SIGNATURES_ARG_ACCOUNT).orEmpty()
            SignaturesScreen(
                onBack = navController::popBackStack,
                onEdit = { signatureId -> navController.navigate(Routes.signatureEdit(accountId, signatureId)) },
                onAdd = { navController.navigate(Routes.signatureEdit(accountId)) },
            )
        }
        composable(
            route = Routes.SIGNATURE_EDIT_PATTERN,
            arguments = listOf(
                navArgument(Routes.SIGNATURE_EDIT_ARG_ACCOUNT) { type = NavType.StringType },
                navArgument(Routes.SIGNATURE_EDIT_ARG_ID) {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) {
            SignatureEditScreen(onBack = navController::popBackStack)
        }
        // "Add account" entry reused by Settings and the mailbox. These reuse the SAME picker/setup
        // screens as onboarding, but each pops back to where the user was on success (no "add
        // another?" prompt — that is onboarding-only, see #30).
        composable(Routes.ACCOUNT_SETUP) {
            AccountPickerScreen(
                onBack = navController::popBackStack,
                onAccountAdded = { navController.popBackStack() },
                onPickProvider = { provider -> navController.navigate(Routes.appPassword(provider.key)) },
                onManualSetup = { navController.navigate(Routes.MANUAL_SETUP) },
            )
        }
        composable(
            route = Routes.APP_PASSWORD_PATTERN,
            arguments = listOf(navArgument(Routes.APP_PASSWORD_ARG_PROVIDER) { type = NavType.StringType }),
        ) {
            AppPasswordSetupScreen(
                onBack = navController::popBackStack,
                onAccountAdded = { navController.popBackStack(Routes.ACCOUNT_SETUP, inclusive = true) },
            )
        }
        composable(Routes.MANUAL_SETUP) {
            ManualSetupScreen(
                onBack = navController::popBackStack,
                onAccountAdded = { navController.popBackStack(Routes.ACCOUNT_SETUP, inclusive = true) },
            )
        }
        composable(Routes.DRAFTS) {
            DraftsScreen(
                onBack = navController::popBackStack,
                onOpenDraft = { id -> navController.navigate(Routes.composeDraft(id)) },
            )
        }
        composable(Routes.OUTBOX) {
            OutboxScreen(onBack = navController::popBackStack)
        }
    }

    // On launch, offer any saved crash report for review — never sent without the user's action.
    pendingCrash?.let { crash ->
        CrashReportDialog(
            onReview = {
                startupViewModel.dismiss()
                navController.navigate(Routes.reportReview(crash.id))
            },
            onLater = startupViewModel::dismiss,
            onDiscard = { startupViewModel.discard(crash.id) },
        )
    }
}

@Composable
private fun CrashReportDialog(onReview: () -> Unit, onLater: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.crash_prompt_title)) },
        text = { Text(stringResource(R.string.crash_prompt_message)) },
        confirmButton = {
            TextButton(onClick = onReview) { Text(stringResource(R.string.crash_prompt_review)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDiscard) { Text(stringResource(R.string.crash_prompt_discard)) }
                TextButton(onClick = onLater) { Text(stringResource(R.string.crash_prompt_later)) }
            }
        },
    )
}

/**
 * First-run onboarding as a nested graph so a single graph-scoped [OnboardingViewModel] can track the
 * first account added this session. The picker/setup screens are the same composables used by the
 * top-level "Add account" routes; here, a successful add routes to the "add another?" prompt instead
 * of popping back.
 *
 * @param licenseAlreadyAccepted decides the graph's start destination (#172): false routes through
 *   [Routes.ONBOARDING_LICENSE] first; true (the user already agreed on a prior run) skips straight to
 *   [Routes.ONBOARDING_WELCOME], matching this graph's pre-#172 behavior.
 */
private fun NavGraphBuilder.onboardingGraph(navController: NavHostController, licenseAlreadyAccepted: Boolean) {
    val onboardingStart = if (licenseAlreadyAccepted) Routes.ONBOARDING_WELCOME else Routes.ONBOARDING_LICENSE
    navigation(startDestination = onboardingStart, route = Routes.ONBOARDING) {
        composable(Routes.ONBOARDING_LICENSE) { entry ->
            val onboarding = onboardingViewModel(navController, entry)
            val activity = LocalActivity.current
            LicenseScreen(
                onAgree = {
                    onboarding.markLicenseAccepted()
                    // Pop LICENSE off the back stack: it is a one-time gate, so a later back-press
                    // from the welcome screen must not be able to return to it.
                    navController.navigate(Routes.ONBOARDING_WELCOME) {
                        popUpTo(Routes.ONBOARDING_LICENSE) { inclusive = true }
                    }
                },
                // MainActivity is this app's only Activity (single-activity Compose app), so finish()
                // is sufficient to exit outright (#172).
                onDecline = { activity?.finish() },
            )
        }
        composable(Routes.ONBOARDING_WELCOME) {
            OnboardingWelcomeScreen(onAddAccount = { navController.navigate(Routes.ONBOARDING_PICKER) })
        }
        composable(Routes.ONBOARDING_PICKER) { entry ->
            val onboarding = onboardingViewModel(navController, entry)
            AccountPickerScreen(
                onBack = navController::popBackStack,
                onAccountAdded = { id ->
                    onboarding.onAccountAdded(id)
                    navController.navigate(Routes.ONBOARDING_ADD_ANOTHER)
                },
                onPickProvider = { provider ->
                    navController.navigate(Routes.onboardingAppPassword(provider.key))
                },
                onManualSetup = { navController.navigate(Routes.ONBOARDING_MANUAL) },
            )
        }
        composable(
            route = Routes.ONBOARDING_APP_PASSWORD_PATTERN,
            arguments = listOf(navArgument(Routes.APP_PASSWORD_ARG_PROVIDER) { type = NavType.StringType }),
        ) { entry ->
            val onboarding = onboardingViewModel(navController, entry)
            AppPasswordSetupScreen(
                onBack = navController::popBackStack,
                onAccountAdded = { id -> onboarding.completeAdd(navController, id) },
            )
        }
        composable(Routes.ONBOARDING_MANUAL) { entry ->
            val onboarding = onboardingViewModel(navController, entry)
            ManualSetupScreen(
                onBack = navController::popBackStack,
                onAccountAdded = { id -> onboarding.completeAdd(navController, id) },
            )
        }
        onboardingFinishDestinations(navController)
    }
}

/**
 * The tail of onboarding: the "add another?" prompt and the optional contacts + battery opt-in steps.
 * Split out of [onboardingGraph] so each stays a readable length; all share the graph-scoped
 * [OnboardingViewModel]. The optional steps chain — contacts (#127) then battery (#49) — each shown
 * only when needed; any that isn't is skipped, and a still-undecided (null) decision fails open.
 */
private fun NavGraphBuilder.onboardingFinishDestinations(navController: NavHostController) {
    composable(Routes.ONBOARDING_ADD_ANOTHER) { entry ->
        val onboarding = onboardingViewModel(navController, entry)
        val contactsPromptNeeded by onboarding.contactsPromptNeeded.collectAsStateWithLifecycle()
        val batteryPromptNeeded by onboarding.batteryPromptNeeded.collectAsStateWithLifecycle()
        AddAnotherAccountScreen(
            onAddAnother = {
                // Return to a fresh picker, clearing the prompt and the prior setup screen.
                navController.navigate(Routes.ONBOARDING_PICKER) {
                    popUpTo(Routes.ONBOARDING_PICKER) { inclusive = true }
                }
            },
            onFinish = { navController.advanceOnboarding(onboarding, contactsPromptNeeded, batteryPromptNeeded) },
        )
    }
    composable(Routes.ONBOARDING_CONTACTS) { entry ->
        val onboarding = onboardingViewModel(navController, entry)
        val batteryPromptNeeded by onboarding.batteryPromptNeeded.collectAsStateWithLifecycle()
        ContactsAccessScreen(
            viewModel = onboarding,
            onFinish = {
                onboarding.markContactsPromptHandled()
                // Contacts is skipped here (it was the step just shown); only battery may remain.
                navController.advanceOnboarding(onboarding, contactsPromptNeeded = false, batteryPromptNeeded)
            },
        )
    }
    composable(Routes.ONBOARDING_BATTERY) { entry ->
        val onboarding = onboardingViewModel(navController, entry)
        BatteryOptimizationScreen(
            viewModel = onboarding,
            onFinish = {
                onboarding.markBatteryPromptHandled()
                navController.finishOnboarding(onboarding.firstAddedAccountId)
            },
        )
    }
}

/**
 * Advances through the optional onboarding tail: the next still-needed opt-in step (contacts, then
 * battery), or the inbox once none remain. Each `*PromptNeeded` is the graph-scoped decision; `null`
 * (undecided) is treated as "not needed" so a slow read never blocks the end of onboarding.
 */
private fun NavHostController.advanceOnboarding(
    onboarding: OnboardingViewModel,
    contactsPromptNeeded: Boolean?,
    batteryPromptNeeded: Boolean?,
) {
    when {
        contactsPromptNeeded == true -> navigate(Routes.ONBOARDING_CONTACTS)
        batteryPromptNeeded == true -> navigate(Routes.ONBOARDING_BATTERY)
        else -> finishOnboarding(onboarding.firstAddedAccountId)
    }
}

/** Leaves onboarding for the inbox — the first account added this session, or the unfiltered mailbox. */
private fun NavController.finishOnboarding(firstAccountId: String?) {
    val dest = if (firstAccountId != null) Routes.mailboxForAccount(firstAccountId) else Routes.MAILBOX
    navigate(dest) {
        // Leave onboarding entirely; the mailbox becomes the new back-stack root.
        popUpTo(Routes.ONBOARDING) { inclusive = true }
    }
}

/** Resolves the onboarding-graph-scoped [OnboardingViewModel] shared across the onboarding screens. */
@Composable
private fun onboardingViewModel(navController: NavController, entry: NavBackStackEntry): OnboardingViewModel {
    val parentEntry = remember(entry) { navController.getBackStackEntry(Routes.ONBOARDING) }
    return hiltViewModel(parentEntry)
}

/** Records the added account, then advances to the "add another?" prompt (dropping the setup form). */
private fun OnboardingViewModel.completeAdd(navController: NavController, accountId: String) {
    onAccountAdded(accountId)
    navController.navigate(Routes.ONBOARDING_ADD_ANOTHER) {
        popUpTo(Routes.ONBOARDING_PICKER)
    }
}

/** Navigate between top-level tabs, preserving each tab's back stack and state. */
private fun NavController.navigateTab(dest: TopDest) {
    navigate(dest.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun LibreMailBottomBar(current: TopDest, onSelect: (TopDest) -> Unit) {
    NavigationBar {
        TopDest.entries.forEach { dest ->
            NavigationBarItem(
                selected = dest == current,
                onClick = { onSelect(dest) },
                icon = { Icon(dest.icon, contentDescription = null) },
                label = { Text(stringResource(dest.labelRes)) },
            )
        }
    }
}
