// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import org.libremail.data.local.entity.AccountEntity

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY sortOrder")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY sortOrder")
    suspend fun getAll(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: AccountEntity)

    /** The sortOrder that appends a new account to the end of the list (0 when there are none yet). */
    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM accounts")
    suspend fun nextSortOrder(): Int

    /**
     * Insert [account] at the end of the user-defined order (issue #164), stamping it with the current
     * max + 1. Both steps run in one transaction so two near-simultaneous adds can't collide on a
     * sortOrder.
     */
    @Transaction
    suspend fun insertAtEnd(account: AccountEntity) {
        upsert(account.copy(sortOrder = nextSortOrder()))
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
