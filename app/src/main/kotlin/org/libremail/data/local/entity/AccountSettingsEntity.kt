// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * Per-account preferences, one row per account. The [accountId] foreign key cascades on delete, so
 * removing an account also removes its settings. It is the primary key, so it is already indexed
 * (no extra `@Index` needed for the foreign key).
 */
@Entity(
    tableName = "account_settings",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class AccountSettingsEntity(
    @PrimaryKey val accountId: String,
    val signature: String = "",
    val signatureEnabled: Boolean = true,
    val notificationsEnabled: Boolean = true,
)
