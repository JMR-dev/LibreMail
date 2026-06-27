// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.libremail.data.local.entity.OutboxEntity

@Dao
interface OutboxDao {
    @Insert
    suspend fun insert(message: OutboxEntity)

    @Query("SELECT * FROM outbox ORDER BY createdAt")
    suspend fun getAll(): List<OutboxEntity>

    @Query("SELECT COUNT(*) FROM outbox")
    fun observeCount(): Flow<Int>

    @Query("UPDATE outbox SET lastError = :error WHERE id = :id")
    suspend fun setError(id: String, error: String?)

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun delete(id: String)
}
