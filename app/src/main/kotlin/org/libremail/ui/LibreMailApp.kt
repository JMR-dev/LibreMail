// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.libremail.R
import org.libremail.ui.accountsetup.AccountSetupScreen
import org.libremail.ui.accountsetup.ManualSetupScreen
import org.libremail.ui.compose.ComposeScreen
import org.libremail.ui.drafts.DraftsScreen
import org.libremail.ui.mailbox.MailboxScreen
import org.libremail.ui.navigation.Routes
import org.libremail.ui.outbox.OutboxScreen
import org.libremail.ui.reader.ReaderScreen
import org.libremail.ui.reporting.ProblemReportsScreen
import org.libremail.ui.reporting.ReportReviewScreen
import org.libremail.ui.reporting.StartupReportViewModel
import org.libremail.ui.settings.AccountSettingsScreen
import org.libremail.ui.settings.SettingsScreen

@Composable
fun LibreMailApp(startupViewModel: StartupReportViewModel = hiltViewModel()) {
    val navController = rememberNavController()
    val pendingCrash by startupViewModel.pendingCrash.collectAsStateWithLifecycle()

    NavHost(
        navController = navController,
        startDestination = Routes.MAILBOX,
    ) {
        composable(Routes.MAILBOX) {
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
                navArgument(Routes.COMPOSE_ARG_SUBJECT) {
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
        ) {
            AccountSettingsScreen(onBack = navController::popBackStack)
        }
        composable(Routes.ACCOUNT_SETUP) {
            AccountSetupScreen(
                onBack = navController::popBackStack,
                onManualSetup = { navController.navigate(Routes.MANUAL_SETUP) },
                onAccountAdded = {
                    navController.navigate(Routes.MAILBOX) {
                        popUpTo(Routes.MAILBOX) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.MANUAL_SETUP) {
            ManualSetupScreen(
                onBack = navController::popBackStack,
                onAccountAdded = {
                    navController.navigate(Routes.MAILBOX) {
                        popUpTo(Routes.MAILBOX) { inclusive = true }
                    }
                },
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
