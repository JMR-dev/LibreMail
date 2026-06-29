// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import org.libremail.data.local.entity.AttachmentEntity

@Dao
interface AttachmentDao {
    @Query("SELECT * FROM attachments WHERE messageId = :messageId ORDER BY partIndex")
    fun observeForMessage(messageId: String): Flow<List<AttachmentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(attachments: List<AttachmentEntity>)

    @Query("DELETE FROM attachments WHERE messageId = :messageId")
    suspend fun deleteForMessage(messageId: String)

    /** Replaces the cached attachment list for a message in one transaction. */
    @Transaction
    suspend fun replaceForMessage(messageId: String, attachments: List<AttachmentEntity>) {
        deleteForMessage(messageId)
        insert(attachments)
    }
}
