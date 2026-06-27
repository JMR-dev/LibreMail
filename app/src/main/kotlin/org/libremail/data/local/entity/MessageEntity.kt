// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    indices = [Index("accountId"), Index("timestampMillis")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val sender: String,
    val senderEmail: String,
    val subject: String,
    val snippet: String,
    val body: String,
    val isHtml: Boolean = false,
    val timestampMillis: Long,
    val isRead: Boolean,
    val isStarred: Boolean,
)
