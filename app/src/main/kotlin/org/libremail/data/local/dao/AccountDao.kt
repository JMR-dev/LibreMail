// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.libremail.data.local.entity.AccountEntity

@Dao
interface AccountDao {
    /**
     * Every listing surface (Settings, the drawer switcher, the unified-inbox filter chips) follows
     * the user-defined [AccountEntity.sortOrder] (issue #164); `email` is only a tiebreaker for rows
     * that share a sortOrder (e.g. inserted via plain [upsert] rather than [insertAtEnd]/[reorder]),
     * so equal-sortOrder accounts still list in a stable, deterministic order.
     */
    @Query("SELECT * FROM accounts ORDER BY sortOrder, email")
    fun observeAll(): Flow<List<AccountEntity>>

    /** See [observeAll] — same ordering: sortOrder, then email as a tiebreaker. */
    @Query("SELECT * FROM accounts ORDER BY sortOrder, email")
    suspend fun getAll(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AccountEntity?

    /**
     * Insert [account] only if its id is absent; a conflicting id is left untouched (returns -1).
     * Non-destructive by design: an `@Insert(REPLACE)` would delete-then-reinsert the row on an id
     * conflict, firing the `ON DELETE CASCADE` that permanently drops the account's `account_settings`
     * + `signatures` (issue #309). Pair it with [update] to refresh an existing row in place instead.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(account: AccountEntity): Long

    /** Refreshes an existing account's columns in place (matched by primary key); no cascade. */
    @Update
    suspend fun update(account: AccountEntity)

    /**
     * Insert [account], or refresh the existing row **in place** when its id is already present —
     * never the delete-then-reinsert an `@Insert(REPLACE)` does, so it does NOT cascade-delete the
     * account's `account_settings` + `signatures` (issue #309). Assigns no list position: new accounts
     * are appended via [insertAtEnd]; a plain upsert leaves [AccountEntity.sortOrder] as supplied.
     */
    @Transaction
    suspend fun upsert(account: AccountEntity) {
        // -1 = the IGNORE insert was skipped because the id already exists → update the row instead.
        if (insertIfAbsent(account) == -1L) update(account)
    }

    /** The sortOrder that appends a new account to the end of the list (0 when there are none yet). */
    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM accounts")
    suspend fun nextSortOrder(): Int

    /**
     * Insert [account] at the end of the user-defined order (issue #164), stamping it with the current
     * max + 1. Re-adding an already-present id (e.g. re-authing an Outlook account, whose id is the
     * deterministic `outlook:<email>`) instead refreshes the row in place, keeping its list position
     * and — crucially — its settings + signatures, which a REPLACE would cascade-delete (issue #309).
     * Runs in one transaction so the read + write can't interleave with a concurrent add.
     */
    @Transaction
    suspend fun insertAtEnd(account: AccountEntity) {
        val existing = getById(account.id)
        if (existing == null) {
            insertIfAbsent(account.copy(sortOrder = nextSortOrder()))
        } else {
            update(account.copy(sortOrder = existing.sortOrder))
        }
    }

    @Query("UPDATE accounts SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun setSortOrder(id: String, sortOrder: Int)

    /**
     * Persist a user-chosen ordering (issue #164): each account is stamped with its index in
     * [orderedIds]. Wrapped in a transaction so the list is never observed half-renumbered.
     */
    @Transaction
    suspend fun reorder(orderedIds: List<String>) {
        orderedIds.forEachIndexed { index, id -> setSortOrder(id, index) }
    }

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun deleteById(id: String)
}
