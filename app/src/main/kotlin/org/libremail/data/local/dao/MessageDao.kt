// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.libremail.data.local.entity.MessageEntity

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY timestampMillis DESC")
    fun observeAll(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MessageEntity?

    /** Inserts only new messages, leaving existing rows (and their cached bodies) intact. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNew(messages: List<MessageEntity>)

    /** Refreshes header/flag columns from the server without touching the cached body. */
    @Query(
        "UPDATE messages SET sender = :sender, senderEmail = :senderEmail, subject = :subject, " +
            "timestampMillis = :timestampMillis, isRead = :isRead, isStarred = :isStarred WHERE id = :id",
    )
    suspend fun updateHeader(
        id: String,
        sender: String,
        senderEmail: String,
        subject: String,
        timestampMillis: Long,
        isRead: Boolean,
        isStarred: Boolean,
    )

    @Query("UPDATE messages SET body = :body, isHtml = :isHtml, snippet = :snippet WHERE id = :id")
    suspend fun updateBody(id: String, body: String, isHtml: Boolean, snippet: String)

    @Query("UPDATE messages SET isRead = :isRead WHERE id = :id")
    suspend fun setRead(id: String, isRead: Boolean)

    @Query("UPDATE messages SET isStarred = :isStarred WHERE id = :id")
    suspend fun setStarred(id: String, isStarred: Boolean)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM messages WHERE accountId = :accountId")
    suspend fun deleteByAccount(accountId: String)

    @Query("DELETE FROM messages WHERE accountId = :accountId AND id NOT IN (:keepIds)")
    suspend fun deleteNotIn(accountId: String, keepIds: List<String>)
}
