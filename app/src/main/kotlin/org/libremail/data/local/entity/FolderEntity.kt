// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/** A cached IMAP folder for an account. [sortOrder] preserves the server's listing order. */
@Entity(tableName = "folders", primaryKeys = ["accountId", "fullName"])
data class FolderEntity(
    val accountId: String,
    val fullName: String,
    val displayName: String,
    /** The [org.libremail.domain.model.FolderRole] name. */
    val role: String,
    val selectable: Boolean,
    val sortOrder: Int,
    /**
     * True when the server advertises this as a special-use folder (RFC 6154, e.g. `\Drafts`,
     * `\Junk`, `\All`). Distinguishes a provider's built-in folder from a same-named user folder
     * when the drawer de-duplicates display labels.
     */
    @ColumnInfo(defaultValue = "0") val specialUse: Boolean = false,
    /**
     * The hierarchy separator the server reported for this folder in its LIST response (e.g. "/" for
     * Gmail, "." for some servers), stored as a one-character string. Null for folders cached before
     * this column existed (legacy rows) or on a flat namespace; the drawer's parent-label logic then
     * infers the separator from the folder name (issue #66). Nullable with no SQL default (the same
     * pattern as the drafts `bodyHtml` column), so the next folder refresh backfills the real value.
     */
    val hierarchyDelimiter: String? = null,
)
