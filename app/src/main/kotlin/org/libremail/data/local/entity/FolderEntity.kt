// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local.entity

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
)
