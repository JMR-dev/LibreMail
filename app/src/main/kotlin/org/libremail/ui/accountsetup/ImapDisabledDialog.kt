// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.accountsetup

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import org.libremail.R

/**
 * Actionable dialog shown when adding an account fails specifically because IMAP is switched off for it
 * (issue #390) — the reactive complement to the pre-auth Outlook notice (#411). Instead of the generic
 * "authentication failed" snackbar, it explains that sign-in succeeded but the server rejected IMAP,
 * and (when [ImapDisabledPrompt.helpUrl] is known) links the provider's page for turning IMAP on.
 *
 * A shared surface reused by every setup screen (the Outlook picker, the app-password form, manual
 * setup) so the message and link stay consistent wherever the failure occurs. The help link is placed
 * as the dialog's *dismiss* action (leading) and "Got it" as the *confirm* action (trailing), so the
 * confirm button — the last control — stays the stable click target for E2E.
 *
 * @param prompt the provider brand + help URL to render.
 * @param onDismiss clears the prompt from state (also fired on outside-tap / back).
 */
@Composable
fun ImapDisabledDialog(prompt: ImapDisabledPrompt, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val helpUrl = prompt.helpUrl
    val message = prompt.brand?.let { stringResource(R.string.imap_disabled_message, it) }
        ?: stringResource(R.string.imap_disabled_message_generic)

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Email, contentDescription = null) },
        title = { Text(stringResource(R.string.imap_disabled_title)) },
        text = { Text(message) },
        // The help link is only offered for providers with a known enable-IMAP page. openUri throws
        // when no browser is installed; swallow it (a rare case) — the primary "Got it" action, which
        // dismisses so the user can fix the toggle and retry, always works. Opening the link
        // deliberately leaves the dialog up so it is still there when the user returns from the browser.
        dismissButton = {
            if (helpUrl != null) {
                TextButton(onClick = { runCatching { uriHandler.openUri(helpUrl) } }) {
                    Text(stringResource(R.string.imap_disabled_help))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.imap_disabled_dismiss)) }
        },
    )
}
