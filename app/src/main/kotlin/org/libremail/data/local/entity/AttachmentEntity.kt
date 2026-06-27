// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/** Cached metadata for one attachment part of a message (the bytes are fetched on demand). */
@Entity(
    tableName = "attachments",
    primaryKeys = ["messageId", "partIndex"],
    indices = [Index("messageId")],
)
data class AttachmentEntity(
    val messageId: String,
    val partIndex: Int,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
)
