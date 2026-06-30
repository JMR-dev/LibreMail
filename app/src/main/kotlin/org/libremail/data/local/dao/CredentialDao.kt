// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.libremail.data.local.entity.CredentialEntity

@Dao
interface CredentialDao {
    @Query("SELECT * FROM credentials WHERE accountId = :accountId LIMIT 1")
    suspend fun getById(accountId: String): CredentialEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(credential: CredentialEntity)

    @Query("DELETE FROM credentials WHERE accountId = :accountId")
    suspend fun deleteById(accountId: String)
}
