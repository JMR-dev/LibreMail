// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import org.libremail.data.local.entity.FolderEntity

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders WHERE accountId = :accountId ORDER BY sortOrder ASC")
    fun observeForAccount(accountId: String): Flow<List<FolderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(folders: List<FolderEntity>)

    @Query("DELETE FROM folders WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)

    /** Replaces an account's folder set with the freshly-listed one (delete-then-insert). */
    @Transaction
    suspend fun replaceForAccount(accountId: String, folders: List<FolderEntity>) {
        deleteForAccount(accountId)
        insertAll(folders)
    }
}
