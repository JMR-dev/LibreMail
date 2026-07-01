// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import org.libremail.R
import org.libremail.ui.navigation.Routes

/** Top-level destinations shown in the bottom navigation bar. */
enum class TopDest(val route: String, val labelRes: Int, val icon: ImageVector) {
    MAILBOX(Routes.MAILBOX, R.string.nav_mailbox, Icons.Filled.Email),
    SETTINGS(Routes.SETTINGS, R.string.nav_settings, Icons.Filled.Settings),
}
