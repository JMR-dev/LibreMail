// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.libremail.data.local.entity.DraftEntity

@Dao
interface DraftDao {
    @Query("SELECT * FROM drafts ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<DraftEntity>>

    @Query("SELECT COUNT(*) FROM drafts")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM drafts WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): DraftEntity?

    /** All drafts, used to check whether an attachment URI is still referenced before releasing its grant. */
    @Query("SELECT * FROM drafts")
    suspend fun getAll(): List<DraftEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(draft: DraftEntity)

    @Query("DELETE FROM drafts WHERE id = :id")
    suspend fun delete(id: String)
}
