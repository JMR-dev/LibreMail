// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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

    /**
     * Reads [accountId]'s row (or null when absent), applies [transform], and writes the result — all in
     * one transaction so a per-field setter's read-modify-write can't interleave with a concurrent
     * setter and clobber the other field (issue #313). [transform] receives the stored entity, or null
     * when no row exists yet.
     */
    @Transaction
    suspend fun readModifyWrite(accountId: String, transform: (AccountSettingsEntity?) -> AccountSettingsEntity) {
        upsert(transform(get(accountId)))
    }
}
