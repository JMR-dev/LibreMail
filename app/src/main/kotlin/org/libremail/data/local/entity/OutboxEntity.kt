// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A message queued for sending. The send worker drains this table and deletes rows on success. */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val toAddresses: String,
    val ccAddresses: String,
    val subject: String,
    val body: String,
    val createdAt: Long,
    val lastError: String? = null,
)
