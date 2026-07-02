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
    /**
     * The message's user-facing attachments for the reader's attachment list. Inline images
     * (`contentId IS NOT NULL`) are excluded — they render in the body via `cid:`, not as downloads
     * (issue #133).
     */
    @Query("SELECT * FROM attachments WHERE messageId = :messageId AND contentId IS NULL ORDER BY partIndex")
    fun observeForMessage(messageId: String): Flow<List<AttachmentEntity>>

    /**
     * One-shot read of ALL of a message's cached parts — attachments AND inline images — e.g. to
     * pre-download their bytes or resolve a `cid:` reference. The reader filters by [AttachmentEntity.contentId].
     */
    @Query("SELECT * FROM attachments WHERE messageId = :messageId ORDER BY partIndex")
    suspend fun getForMessage(messageId: String): List<AttachmentEntity>

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
