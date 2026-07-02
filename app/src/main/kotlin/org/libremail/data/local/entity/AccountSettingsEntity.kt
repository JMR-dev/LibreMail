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
    /**
     * Per-account device-only retention overrides (issue #13). `null` means "use the global default";
     * `0` means an explicit "keep everything" (no limit). A positive value caps how many messages
     * ([retentionCount], newest per folder) or how many months of history ([retentionMonths]) are kept
     * on this device — the server copy is never touched.
     */
    val retentionCount: Int? = null,
    val retentionMonths: Int? = null,
)
