// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local.entity

import androidx.room.Entity

/**
 * Per-(account, folder) progress of the full-history backfill (issue #12). Lets the background
 * backfill worker page backwards through a folder resumably: it survives process death and network
 * loss because the boundary is persisted after every batch.
 *
 * Not foreign-keyed to `accounts` (matching `messages`/`folders`); it is cleared explicitly when an
 * account is removed.
 */
@Entity(tableName = "backfill_progress", primaryKeys = ["accountId", "folder"])
data class BackfillProgressEntity(
    val accountId: String,
    val folder: String,
    /**
     * Exclusive upper UID bound for the next page: the next batch fetches server messages with
     * UID < this value. Lowered to the batch's lowest UID after each successful page.
     */
    val nextBeforeUid: Long,
    /** True once the whole folder (down to the retention floor, if any) has been cached. */
    val complete: Boolean = false,
)
