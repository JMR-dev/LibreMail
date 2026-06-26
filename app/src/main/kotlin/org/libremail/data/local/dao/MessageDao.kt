// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import org.libremail.data.local.entity.MessageEntity

@Dao
abstract class MessageDao {
    @Query("SELECT * FROM messages ORDER BY timestampMillis DESC")
    abstract fun observeAll(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    abstract suspend fun getById(id: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertAll(messages: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE accountId = :accountId")
    abstract suspend fun deleteByAccount(accountId: String)

    /** Replace an account's cached messages with a freshly fetched set, atomically. */
    @Transaction
    open suspend fun replaceAccountMessages(accountId: String, messages: List<MessageEntity>) {
        deleteByAccount(accountId)
        upsertAll(messages)
    }
}
