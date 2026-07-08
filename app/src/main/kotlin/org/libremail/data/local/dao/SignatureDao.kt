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

    /**
     * Inserts [signature], making it the account's default when it is the account's first — the count
     * and the insert run in one transaction so two concurrent first-creates can't both read "count 0"
     * and both become default (issue #313). [signature]'s own `isDefault` is ignored: this method
     * decides it from the current count.
     */
    @Transaction
    suspend fun insertMakingFirstDefault(signature: SignatureEntity) {
        upsert(signature.copy(isDefault = countForAccount(signature.accountId) == 0))
    }

    /**
     * Deletes [id] and, when it was the account's default, promotes the account's first remaining
     * signature — both in one transaction so a crash between the delete and the promote can't leave an
     * account with signatures but no default (issue #313). No-op when [id] is absent. Returns the id of
     * the signature promoted to default, or null when nothing was promoted (id absent, the deleted row
     * wasn't the default, or no signatures remain).
     */
    @Transaction
    suspend fun deletePromotingDefault(id: String): String? {
        val existing = getById(id) ?: return null
        delete(id)
        if (!existing.isDefault) return null
        val promoted = firstForAccount(existing.accountId) ?: return null
        markDefault(promoted.id)
        return promoted.id
    }
}
