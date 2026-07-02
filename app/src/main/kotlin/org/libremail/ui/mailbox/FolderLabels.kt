// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.mailbox

import org.libremail.domain.model.Account
import org.libremail.domain.model.AuthType
import org.libremail.domain.model.Folder
import org.libremail.domain.model.MailProvider

/**
 * The provider name appended when de-duplicating a folder label (the "Gmail" in "Drafts - Gmail").
 * A recognized brand where possible, else the account's email domain so any account has a suffix.
 */
fun providerLabel(account: Account): String = when {
    account.authType == AuthType.OAUTH_OUTLOOK ||
        account.imap.host.contains("outlook", ignoreCase = true) ||
        account.imap.host.contains("office365", ignoreCase = true) -> "Outlook"

    else -> MailProvider.forImapHost(account.imap.host)?.displayName
        ?: account.email.substringAfterLast('@', account.imap.host)
}

/**
 * The provider suffix used when de-duplicating [folders]' labels, derived from the account that owns
 * the listed folders themselves. While switching accounts, the drawer's account updates before its
 * folder list catches up, so a still-rendered stale list must keep its own account's suffix instead
 * of borrowing the incoming account's brand. Empty when [folders] is empty or its owner is not in
 * [accounts] (no suffix is better than a wrong one).
 */
fun providerLabelFor(folders: List<Folder>, accounts: List<Account>): String =
    accounts.firstOrNull { it.id == folders.firstOrNull()?.accountId }?.let(::providerLabel).orEmpty()

/**
 * Resolves each folder's user-facing label so folders that would render the same name are told apart
 * (used by the drawer, the move-to picker, and the app-bar title). [baseLabels] maps a folder's
 * [Folder.fullName] to its localized base label (a friendly role name, or the raw display name).
 *
 * A base label shared by 2+ folders is disambiguated: the provider's built-in special folder gets
 * " - [providerLabel]" (e.g. "Archive - Gmail"); a nested user folder gets its parent location in
 * parentheses (e.g. "Reports (Work)"); a top-level user folder keeps the base label only when that
 * is its own name, otherwise it shows its real name (see [userFolderLabel]). These rules cover the
 * common collisions but not every one (e.g. "Work/2024/Reports" and "Home/2024/Reports" both yield
 * "Reports (2024)"), so a final pass appends the full path to any labels that still tie, telling
 * the tied entries apart.
 */
fun resolveDrawerLabels(
    folders: List<Folder>,
    baseLabels: Map<String, String>,
    providerLabel: String,
): Map<String, String> {
    fun base(folder: Folder) = baseLabels[folder.fullName] ?: folder.displayName
    val duplicated = folders.groupingBy(::base).eachCount().filterValues { it > 1 }.keys

    val resolved = folders.associate { folder ->
        val base = base(folder)
        val label = when {
            base !in duplicated -> base
            folder.specialUse -> "$base - $providerLabel"
            else -> userFolderLabel(folder, base)
        }
        folder.fullName to label
    }

    // Safety net for a residual tie (e.g. two special folders mapping to one role): fall back to the
    // unambiguous full path so the tied entries stay apart.
    val stillTied = resolved.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    if (stillTied.isEmpty()) return resolved
    return resolved.mapValues { (fullName, label) -> if (label in stillTied) "$label [$fullName]" else label }
}

/**
 * A colliding user folder's label. A nested folder shows its parent location. Top-level folders
 * tie-break on the display name: several can share one role-derived base label (on servers without
 * SPECIAL-USE, "Sent" and "Sent Items" both classify as the Sent role), so only the folder actually
 * named like the base keeps it — the others show their real server name rather than falling through
 * to the safety net as a self-referential "Sent [Sent]".
 */
private fun userFolderLabel(folder: Folder, base: String): String {
    val parent = parentOf(folder.fullName, folder.displayName)
    return when {
        parent != null -> "$base ($parent)"
        folder.displayName.equals(base, ignoreCase = true) -> base
        else -> folder.displayName
    }
}

/**
 * The immediate parent segment of [fullName] (its location), or null when the folder is top-level.
 * [displayName] is the leaf, so the character just before it in [fullName] is the server's hierarchy
 * separator and the segment before that is the parent.
 */
private fun parentOf(fullName: String, displayName: String): String? {
    if (fullName.length <= displayName.length || !fullName.endsWith(displayName)) return null
    val separator = fullName[fullName.length - displayName.length - 1]
    val parentPath = fullName.substring(0, fullName.length - displayName.length - 1)
    return parentPath.substringAfterLast(separator).ifEmpty { null }
}
