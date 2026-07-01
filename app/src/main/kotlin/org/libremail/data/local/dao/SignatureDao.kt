// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import org.libremail.data.local.entity.SignatureEntity

@Dao
interface SignatureDao {
    @Query("SELECT * FROM signatures WHERE accountId = :accountId ORDER BY isDefault DESC, name COLLATE NOCASE")
    fun observeForAccount(accountId: String): Flow<List<SignatureEntity>>

    @Query("SELECT * FROM signatures WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SignatureEntity?

    @Query("SELECT * FROM signatures WHERE accountId = :accountId AND isDefault = 1 LIMIT 1")
    suspend fun getDefault(accountId: String): SignatureEntity?

    @Query("SELECT * FROM signatures WHERE accountId = :accountId ORDER BY name COLLATE NOCASE LIMIT 1")
    suspend fun firstForAccount(accountId: String): SignatureEntity?

    @Query("SELECT COUNT(*) FROM signatures WHERE accountId = :accountId")
    suspend fun countForAccount(accountId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(signature: SignatureEntity)

    @Query("DELETE FROM signatures WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE signatures SET isDefault = 0 WHERE accountId = :accountId")
    suspend fun clearDefault(accountId: String)

    @Query("UPDATE signatures SET isDefault = 1 WHERE id = :id")
    suspend fun markDefault(id: String)

    /** Makes [id] the account's sole default in one transaction (clears the others first). */
    @Transaction
    suspend fun setDefault(accountId: String, id: String) {
        clearDefault(accountId)
        markDefault(id)
    }
}
