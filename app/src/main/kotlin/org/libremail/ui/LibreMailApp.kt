// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.libremail.ui.accountsetup.AccountSetupScreen
import org.libremail.ui.accountsetup.ManualSetupScreen
import org.libremail.ui.compose.ComposePrefill
import org.libremail.ui.compose.ComposeScreen
import org.libremail.ui.drafts.DraftsScreen
import org.libremail.ui.mailbox.MailboxScreen
import org.libremail.ui.navigation.Routes
import org.libremail.ui.outbox.OutboxScreen
import org.libremail.ui.reader.ReaderScreen
import org.libremail.ui.settings.AccountSettingsScreen
import org.libremail.ui.settings.SettingsScreen

@Composable
fun LibreMailApp(pendingCompose: ComposePrefill? = null, onComposeHandled: () -> Unit = {}) {
    val navController = rememberNavController()

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
            )
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
