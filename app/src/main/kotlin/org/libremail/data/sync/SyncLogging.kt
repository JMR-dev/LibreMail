// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

/**
 * The folder name safe to write to an [org.libremail.reporting.AppLog] breadcrumb (and so, in turn, a
 * submitted [org.libremail.reporting.DebugReport]): the leaf name itself for a known **system** folder
 * (INBOX, Sent, Drafts, Trash, Spam/Junk, Archive — including the alternate names real IMAP servers use
 * for them), or a fixed placeholder for anything else. A user-created folder or label (e.g. a client
 * name or project) can be PII-ish, so only this fixed, closed set of well-known names is ever logged
 * verbatim; every other folder logs as the placeholder, regardless of nesting or the server's hierarchy
 * delimiter. Matching is name-only — no server SPECIAL-USE attributes are available down here at the
 * sync layer — so it is necessarily best-effort in the same way
 * [org.libremail.domain.model.FolderRole.roleOf]'s display-name fallback is. That is the safe direction:
 * a false negative just logs the placeholder, never a leaked name.
 */
internal fun logSafeFolderLabel(folder: String): String {
    val leaf = folder.substringAfterLast('/').substringAfterLast('.').trim()
    return if (leaf.lowercase() in SYSTEM_FOLDER_NAMES) leaf else FOLDER_PLACEHOLDER
}

private const val FOLDER_PLACEHOLDER = "<folder>"

/** Case-insensitive leaf names recognized as provider-supplied system folders, never user-created. */
private val SYSTEM_FOLDER_NAMES = setOf(
    "inbox",
    "sent",
    "sent mail",
    "sent items",
    "sent messages",
    "drafts",
    "draft",
    "junk",
    "spam",
    "junk e-mail",
    "junk email",
    "bulk mail",
    "trash",
    "deleted",
    "deleted items",
    "deleted messages",
    "bin",
    "archive",
    "archives",
    "all mail",
)
