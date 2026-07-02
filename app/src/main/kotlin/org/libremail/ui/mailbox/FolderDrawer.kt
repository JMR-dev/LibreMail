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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.libremail.R
import org.libremail.domain.model.Account
import org.libremail.domain.model.Folder
import org.libremail.domain.model.FolderRole

/**
 * The navigation drawer's contents: an optional account switcher and "All Inboxes" entry (only with
 * 2+ accounts), then the drawer account's folders — standard folders (Inbox, Sent, …) first.
 */
@Composable
fun FolderDrawer(
    accounts: List<Account>,
    drawerAccount: Account?,
    folders: List<Folder>,
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
            AccountSwitcher(accounts, drawerAccount, onSelectDrawerAccount)
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
            NavigationDrawerItem(
                label = { Text(resolvedLabels[folder.fullName] ?: folderDisplayLabel(folder)) },
                icon = iconContent,
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
private fun AccountSwitcher(accounts: List<Account>, current: Account, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(
        onClick = { expanded = true },
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        Text(current.email, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(R.string.drawer_switch_account))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        accounts.forEach { account ->
            DropdownMenuItem(
                text = { Text(account.email) },
                onClick = {
                    onSelect(account.id)
                    expanded = false
                },
            )
        }
    }
}

/**
 * De-duplicated display labels for [folders], keyed by [Folder.fullName] — the one resolution used
 * by the drawer, the move-to picker, and the app-bar title, so a folder reads the same everywhere.
 * The provider suffix derives from the account owning the listed folders (see [providerLabelFor]),
 * not from whichever account is currently selected, so a folder list that briefly lags an account
 * switch never borrows the incoming account's brand.
 */
@Composable
fun resolvedFolderLabels(folders: List<Folder>, accounts: List<Account>): Map<String, String> {
    val baseLabels = folders.associate { it.fullName to folderDisplayLabel(it) }
    return resolveDrawerLabels(folders, baseLabels, providerLabelFor(folders, accounts))
}

/** The user-facing label for a folder: a friendly name for standard roles, else the server name. */
@Composable
fun folderDisplayLabel(folder: Folder): String = when (folder.role) {
    FolderRole.INBOX -> stringResource(R.string.folder_inbox)
    FolderRole.SENT -> stringResource(R.string.folder_sent)
    FolderRole.DRAFTS -> stringResource(R.string.folder_drafts)
    FolderRole.ARCHIVE -> stringResource(R.string.folder_archive)
    FolderRole.SPAM -> stringResource(R.string.folder_spam)
    FolderRole.TRASH -> stringResource(R.string.folder_trash)
    FolderRole.NORMAL -> folder.displayName
}

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
