// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One saved signature. The [accountId] foreign key cascades on delete, so removing an account drops
 * its signatures. [isDefault] marks the one auto-inserted when composing from the account; the
 * repository keeps at most one default per account.
 */
@Entity(
    tableName = "signatures",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("accountId")],
)
data class SignatureEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val name: String,
    val contentHtml: String,
    val isDefault: Boolean = false,
)
