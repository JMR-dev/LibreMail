// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.libremail.R
import org.libremail.ui.accountsetup.AccountSetupScreen
import org.libremail.ui.compose.ComposeScreen
import org.libremail.ui.mailbox.MailboxScreen
import org.libremail.ui.navigation.Routes
import org.libremail.ui.reader.ReaderScreen
import org.libremail.ui.settings.SettingsScreen

/** Top-level destinations shown in the bottom navigation bar. */
enum class TopDest(val route: String, val labelRes: Int, val icon: ImageVector) {
    MAILBOX(Routes.MAILBOX, R.string.nav_mailbox, Icons.Filled.Email),
    SETTINGS(Routes.SETTINGS, R.string.nav_settings, Icons.Filled.Settings),
}

@Composable
fun LibreMailApp() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.MAILBOX,
    ) {
        composable(Routes.MAILBOX) {
            MailboxScreen(
                onOpenMessage = { id -> navController.navigate(Routes.reader(id)) },
                onCompose = { navController.navigate(Routes.COMPOSE) },
                onSelectTab = navController::navigateTab,
            )
        }
        composable(
            route = Routes.READER_PATTERN,
            arguments = listOf(navArgument(Routes.READER_ARG_ID) { type = NavType.StringType }),
        ) {
            ReaderScreen(onBack = navController::popBackStack)
        }
        composable(Routes.COMPOSE) {
            ComposeScreen(onBack = navController::popBackStack)
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onAddAccount = { navController.navigate(Routes.ACCOUNT_SETUP) },
                onSelectTab = navController::navigateTab,
            )
        }
        composable(Routes.ACCOUNT_SETUP) {
            AccountSetupScreen(onBack = navController::popBackStack)
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
