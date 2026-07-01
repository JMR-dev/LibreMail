// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.libremail.data.local.entity.AccountSettingsEntity

@Dao
interface AccountSettingsDao {
    @Query("SELECT * FROM account_settings WHERE accountId = :accountId LIMIT 1")
    fun observe(accountId: String): Flow<AccountSettingsEntity?>

    @Query("SELECT * FROM account_settings WHERE accountId = :accountId LIMIT 1")
    suspend fun get(accountId: String): AccountSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: AccountSettingsEntity)
}
