// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.mailbox

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.libremail.R
import org.libremail.domain.model.Account
import org.libremail.domain.model.Folder
import org.libremail.domain.model.FolderRole

/**
 * The navigation drawer's contents: an optional account switcher and "All Inboxes" entry (only with
 * 2+ accounts), then the drawer account's folders — standard folders (Inbox, Sent, …) first. Each
 * folder shows its unread count as a trailing badge ([folderUnreadCounts]), and accounts with unread
 * mail ([accountsWithUnread]) render their email in bold.
 */
@Composable
fun FolderDrawer(
    accounts: List<Account>,
    drawerAccount: Account?,
    folders: List<Folder>,
    folderUnreadCounts: Map<String, Int>,
    accountsWithUnread: Set<String>,
    selectedAccountId: String?,
    selectedFolder: String,
    onSelectUnifiedInbox: () -> Unit,
    onSelectFolder: (accountId: String, folderFullName: String) -> Unit,
    onSelectDrawerAccount: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
        )

        if (accounts.size >= 2 && drawerAccount != null) {
            AccountSwitcher(accounts, drawerAccount, accountsWithUnread, onSelectDrawerAccount)
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.folder_all_inboxes)) },
                icon = { Icon(Icons.Filled.Email, contentDescription = null) },
                selected = selectedAccountId == null && selectedFolder == INBOX,
                onClick = onSelectUnifiedInbox,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }

        val sorted = remember(folders) {
            folders.sortedWith(compareBy({ it.role.ordinal }, { it.displayName.lowercase() }))
        }
        // De-duplicate labels: two distinct folders that would render the same name (e.g. a
        // provider's built-in Drafts and a same-named user folder) get disambiguated.
        val resolvedLabels = resolvedFolderLabels(sorted, accounts)
        sorted.forEach { folder ->
            val isSelected = selectedAccountId != null &&
                selectedAccountId == drawerAccount?.id &&
                folder.fullName == selectedFolder
            val iconContent: (@Composable () -> Unit)? = folderIcon(folder.role)?.let { vector ->
                { Icon(vector, contentDescription = null) }
            }
            val unread = folderUnreadCounts[folder.fullName] ?: 0
            NavigationDrawerItem(
                // getValue: resolvedLabels is keyed by every folder in this same list, so a miss is
                // a bug to surface, not to paper over with a re-derived (un-deduplicated) label.
                label = { Text(resolvedLabels.getValue(folder.fullName)) },
                icon = iconContent,
                badge = if (unread > 0) {
                    { UnreadBadgeLabel(unread) }
                } else {
                    null
                },
                selected = isSelected,
                onClick = {
                    if (folder.selectable && drawerAccount != null) {
                        onSelectFolder(drawerAccount.id, folder.fullName)
                    }
                },
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}

@Composable
private fun AccountSwitcher(
    accounts: List<Account>,
    current: Account,
    accountsWithUnread: Set<String>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(
        onClick = { expanded = true },
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        Text(
            current.email,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = if (current.id in accountsWithUnread) FontWeight.Bold else FontWeight.Normal,
        )
        Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(R.string.drawer_switch_account))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        accounts.forEach { account ->
            DropdownMenuItem(
                text = {
                    Text(
                        account.email,
                        fontWeight = if (account.id in accountsWithUnread) FontWeight.Bold else FontWeight.Normal,
                    )
                },
                onClick = {
                    onSelect(account.id)
                    expanded = false
                },
            )
        }
    }
}

/** Cap for a folder's visible unread badge; higher counts render as "99+" to keep the row compact. */
private const val UNREAD_BADGE_CAP = 99

/**
 * A folder row's trailing unread-count badge. The visible label is capped at [UNREAD_BADGE_CAP] as
 * "99+" so a large count can't blow out the row, while the semantics announce the exact count as
 * "N unread messages" (overriding the terse glyph) for screen readers.
 */
@Composable
private fun UnreadBadgeLabel(count: Int) {
    val display = if (count > UNREAD_BADGE_CAP) {
        stringResource(R.string.folder_unread_overflow)
    } else {
        count.toString()
    }
    val description = pluralStringResource(R.plurals.folder_unread_count_description, count, count)
    Text(
        text = display,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.clearAndSetSemantics { contentDescription = description },
    )
}

/**
 * De-duplicated display labels for [folders], keyed by [Folder.fullName] — the one resolution used
 * by the drawer, the move-to picker, and the app-bar title, so a folder reads the same everywhere.
 * The provider suffix derives from the account owning the listed folders (see [providerLabelFor]),
 * not from whichever account is currently selected, so a folder list that briefly lags an account
 * switch never borrows the incoming account's brand.
 *
 * The Compose-only lookups (the role→name strings and the localized disambiguation [LabelPatterns])
 * are hoisted out of the [remember] calculation — `stringResource` can't run inside its
 * `@DisallowComposableCalls` body — which then memoizes the resolved map, rebuilding it only when
 * the folders, accounts, or those strings change. Callers that compose while idle (the closed
 * navigation drawer) and taps that change only the selection then reuse the same map instance.
 */
@Composable
fun resolvedFolderLabels(folders: List<Folder>, accounts: List<Account>): Map<String, String> {
    val roleLabels = folderRoleLabels()
    val patterns = LabelPatterns(
        withProvider = stringResource(R.string.folder_label_with_provider),
        withParent = stringResource(R.string.folder_label_with_parent),
        withPath = stringResource(R.string.folder_label_with_path),
    )
    return remember(folders, accounts, roleLabels, patterns) {
        val baseLabels = folders.associate { folder ->
            folder.fullName to (roleLabels[folder.role] ?: folder.displayName)
        }
        resolveDrawerLabels(folders, baseLabels, providerLabelFor(folders, accounts), patterns)
    }
}

/** The localized friendly names for the standard folder roles; [FolderRole.NORMAL] has none. */
@Composable
private fun folderRoleLabels(): Map<FolderRole, String> = mapOf(
    FolderRole.INBOX to stringResource(R.string.folder_inbox),
    FolderRole.SENT to stringResource(R.string.folder_sent),
    FolderRole.DRAFTS to stringResource(R.string.folder_drafts),
    FolderRole.ARCHIVE to stringResource(R.string.folder_archive),
    FolderRole.SPAM to stringResource(R.string.folder_spam),
    FolderRole.TRASH to stringResource(R.string.folder_trash),
)

/** The user-facing label for a folder: a friendly name for standard roles, else the server name. */
@Composable
fun folderDisplayLabel(folder: Folder): String = folderRoleLabels()[folder.role] ?: folder.displayName

/** A leading icon for standard folders, limited to the material-icons-core set (null = no icon). */
private fun folderIcon(role: FolderRole): ImageVector? = when (role) {
    FolderRole.INBOX -> Icons.Filled.Email
    FolderRole.SENT -> Icons.AutoMirrored.Filled.Send
    FolderRole.DRAFTS -> Icons.Filled.Edit
    FolderRole.SPAM -> Icons.Filled.Delete
    FolderRole.TRASH -> Icons.Filled.Delete
    FolderRole.ARCHIVE -> null
    FolderRole.NORMAL -> null
}
