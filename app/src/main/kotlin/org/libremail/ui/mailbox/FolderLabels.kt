// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.mailbox

import org.libremail.domain.model.Account
import org.libremail.domain.model.Folder
import org.libremail.domain.model.MailProvider

/**
 * The provider name appended when de-duplicating a folder label (the "Gmail" in "Drafts - Gmail").
 * A recognized brand where possible (resolved through [MailProvider.brandFor], the single source of
 * host→brand knowledge), else the account's email domain so any account still gets a suffix.
 */
fun providerLabel(account: Account): String =
    MailProvider.brandFor(account) ?: account.email.substringAfterLast('@', account.imap.host)

/**
 * The provider suffix used when de-duplicating [folders]' labels, derived from the account that owns
 * the listed folders themselves. While switching accounts, the drawer's account updates before its
 * folder list catches up, so a still-rendered stale list must keep its own account's suffix instead
 * of borrowing the incoming account's brand. Empty when [folders] is empty or its owner is not in
 * [accounts] (no suffix is better than a wrong one).
 */
fun providerLabelFor(folders: List<Folder>, accounts: List<Account>): String =
    accounts.firstOrNull { it.id == folders.firstOrNull()?.accountId }?.let(::providerLabel).orEmpty()

// Default disambiguation patterns. These mirror the strings.xml resources of the same names, and the
// literals used before the patterns were externalized (#68); the localized resources are supplied by
// the composable call site (see resolvedFolderLabels). "%1$s" is the base label, "%2$s" the detail.
private const val PROVIDER_LABEL_PATTERN = "%1\$s - %2\$s"
private const val PARENT_LABEL_PATTERN = "%1\$s (%2\$s)"
private const val PATH_LABEL_PATTERN = "%1\$s [%2\$s]"

/**
 * The locale-specific patterns for joining a folder's base label with its disambiguating detail.
 * Defaults match the pre-i18n literals so [resolveDrawerLabels] stays a pure, JVM-testable function;
 * the drawer supplies the localized strings.xml values (the folder_label_with_* strings).
 */
data class LabelPatterns(
    val withProvider: String = PROVIDER_LABEL_PATTERN,
    val withParent: String = PARENT_LABEL_PATTERN,
    val withPath: String = PATH_LABEL_PATTERN,
)

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
    patterns: LabelPatterns = LabelPatterns(),
): Map<String, String> {
    // getValue, not a fallback: baseLabels is built from this same folder list, so a miss is a
    // programming error that should fail loudly rather than silently revert to an un-deduped label.
    fun base(folder: Folder) = baseLabels.getValue(folder.fullName)
    val duplicated = folders.groupingBy(::base).eachCount().filterValues { it > 1 }.keys
    // Dominant case: nothing collides, so every label is already its base. Return the base labels
    // as-is rather than rebuilding a value-identical map (and re-running the counting pass below).
    if (duplicated.isEmpty()) return baseLabels

    val resolved = folders.associate { folder ->
        val base = base(folder)
        val label = when {
            base !in duplicated -> base
            folder.specialUse -> patterns.withProvider.format(base, providerLabel)
            else -> userFolderLabel(folder, base, patterns.withParent)
        }
        folder.fullName to label
    }

    // Safety net for a residual tie (e.g. two special folders mapping to one role): fall back to the
    // unambiguous full path so the tied entries stay apart.
    val stillTied = resolved.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    if (stillTied.isEmpty()) return resolved
    return resolved.mapValues { (fullName, label) ->
        if (label in stillTied) patterns.withPath.format(label, fullName) else label
    }
}

/**
 * A colliding user folder's label. A nested folder shows its parent location. Top-level folders
 * tie-break on the display name: several can share one role-derived base label (on servers without
 * SPECIAL-USE, "Sent" and "Sent Items" both classify as the Sent role), so only the folder actually
 * named like the base keeps it — the others show their real server name rather than falling through
 * to the safety net as a self-referential "Sent [Sent]".
 */
private fun userFolderLabel(folder: Folder, base: String, parentPattern: String): String {
    val parent = parentOf(folder)
    return when {
        parent != null -> parentPattern.format(base, parent)
        folder.displayName.equals(base, ignoreCase = true) -> base
        else -> folder.displayName
    }
}

/**
 * The immediate parent segment of [folder]'s path (its location), or null when the folder is
 * top-level. Splits [Folder.fullName] on the server-reported [Folder.hierarchyDelimiter] so a folder
 * name that happens to contain a separator-looking character can't be mis-parented (issue #66). For
 * legacy rows cached before the delimiter was persisted (a null delimiter) it falls back to inferring
 * the separator as the character immediately before the [Folder.displayName] leaf.
 */
private fun parentOf(folder: Folder): String? {
    val separator = folder.hierarchyDelimiter
        ?: inferSeparator(folder.fullName, folder.displayName)
        ?: return null
    val parentPath = folder.fullName.substringBeforeLast(separator, missingDelimiterValue = "")
    return parentPath.substringAfterLast(separator).ifEmpty { null }
}

/**
 * Legacy fallback separator for rows without a persisted delimiter: the character just before the
 * [displayName] leaf in [fullName], or null when [displayName] is not a suffix of [fullName] (then
 * the folder is treated as top-level, exactly as before the delimiter was persisted).
 */
private fun inferSeparator(fullName: String, displayName: String): Char? {
    if (fullName.length <= displayName.length || !fullName.endsWith(displayName)) return null
    return fullName[fullName.length - displayName.length - 1]
}
