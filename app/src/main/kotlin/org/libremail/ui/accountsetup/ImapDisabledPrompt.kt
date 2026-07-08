// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.accountsetup

import org.libremail.domain.model.Account
import org.libremail.domain.model.MailProvider
import org.libremail.mail.ImapAuthError

/**
 * The data an "IMAP is turned off" prompt needs (issue #390): the provider [brand] for the message
 * copy (null when the host maps to no known brand — a generic message is shown), and the [helpUrl] to
 * the provider's enable-IMAP page (null when we have no provider-specific page, so no help link is
 * offered). Built by [imapDisabledPromptFor] from a classified auth failure.
 */
data class ImapDisabledPrompt(val brand: String?, val helpUrl: String?)

/**
 * Returns the actionable prompt to show when [error] — the failure from adding [account] — is an
 * "IMAP access is disabled" auth rejection ([ImapAuthError.isImapDisabled]), or null for any other
 * failure (which keeps the existing generic error). [usedOAuth] is true only on the Outlook XOAUTH2
 * path, where a valid-token `AUTHENTICATE` rejection is itself the IMAP-disabled signal.
 *
 * Provider awareness comes from [account]: [MailProvider.brandFor] names the brand (Outlook by auth
 * type/host, the app-password vendors by IMAP host — so even a manually-configured Gmail account is
 * recognised), and [enableImapHelpUrl] maps it to the provider's help page.
 */
fun imapDisabledPromptFor(error: Throwable, account: Account, usedOAuth: Boolean): ImapDisabledPrompt? {
    if (!ImapAuthError.isImapDisabled(error, usedOAuth)) return null
    val brand = MailProvider.brandFor(account)
    return ImapDisabledPrompt(brand = brand, helpUrl = enableImapHelpUrl(brand))
}

/**
 * The provider's own "how to turn IMAP on" help page for a recognised [brand], or null for brands with
 * no user-facing IMAP toggle we can link (Yahoo/iCloud/AOL gate access through app passwords, not an
 * IMAP switch) or an unrecognised host — those get the generic message with no link.
 */
private fun enableImapHelpUrl(brand: String?): String? = when (brand) {
    MailProvider.OUTLOOK_BRAND -> OUTLOOK_IMAP_HELP_URL
    MailProvider.GMAIL.displayName -> GMAIL_IMAP_HELP_URL
    else -> null
}

// Microsoft's canonical "POP, IMAP, and SMTP settings for Outlook.com" support article — the
// authoritative walkthrough for the "Let devices and apps use POP/IMAP" toggle (verified 2026-07,
// issue #390). Matches the pre-auth notice in #411 so both directions point users to one page.
private const val OUTLOOK_IMAP_HELP_URL =
    "https://support.microsoft.com/en-us/office/pop-imap-and-smtp-settings-for-outlook-com-" +
        "d088b986-291d-42b8-9564-9c414e2aa040"

// Google's "Check Gmail through other email platforms" article, which documents Settings -> See all
// settings -> Forwarding and POP/IMAP -> Enable IMAP (verified 2026-07, issue #390).
private const val GMAIL_IMAP_HELP_URL = "https://support.google.com/mail/answer/7126229"
