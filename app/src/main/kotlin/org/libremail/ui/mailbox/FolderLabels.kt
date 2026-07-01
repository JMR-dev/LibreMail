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
 * Resolves each folder's drawer label so no two entries collide. [baseLabels] maps a folder's
 * [Folder.fullName] to its localized base label (a friendly role name, or the raw display name).
 *
 * A base label shared by 2+ folders is disambiguated: the provider's built-in special folder gets
 * " - [providerLabel]" (e.g. "Archive - Gmail"); a nested user folder gets its parent location in
 * parentheses (e.g. "Reports (Work)"); a top-level user folder keeps its base label. Among colliding
 * user folders at most one is top-level (paths are unique), so the result is distinct. A final pass
 * appends the full path to any labels that still tie, guaranteeing uniqueness.
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
            else -> parentOf(folder.fullName, folder.displayName)?.let { "$base ($it)" } ?: base
        }
        folder.fullName to label
    }

    // Safety net for a residual tie (e.g. two special folders mapping to one role): fall back to the
    // unambiguous full path so every drawer entry stays distinct.
    val stillTied = resolved.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    if (stillTied.isEmpty()) return resolved
    return resolved.mapValues { (fullName, label) -> if (label in stillTied) "$label [$fullName]" else label }
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
