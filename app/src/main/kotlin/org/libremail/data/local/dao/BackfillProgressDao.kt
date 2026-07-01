// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.libremail.data.local.entity.BackfillProgressEntity

@Dao
interface BackfillProgressDao {
    @Query("SELECT * FROM backfill_progress WHERE accountId = :accountId AND folder = :folder LIMIT 1")
    suspend fun get(accountId: String, folder: String): BackfillProgressEntity?

    @Query("SELECT * FROM backfill_progress WHERE accountId = :accountId")
    suspend fun getForAccount(accountId: String): List<BackfillProgressEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: BackfillProgressEntity)

    @Query("DELETE FROM backfill_progress WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)
}
