// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** A composed-but-unsent message saved for later editing. */
@Entity(tableName = "drafts")
data class DraftEntity(
    @PrimaryKey val id: String,
    val accountId: String?,
    val toAddresses: String,
    val ccAddresses: String,
    @ColumnInfo(defaultValue = "") val bccAddresses: String = "",
    val subject: String,
    val body: String,
    val updatedAt: Long,
    /** JSON array of the draft's attachments ([uri, name] pairs); empty string when there are none. */
    val attachments: String = "",
)
