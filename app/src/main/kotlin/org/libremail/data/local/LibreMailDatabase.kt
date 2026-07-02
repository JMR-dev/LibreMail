// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import org.libremail.data.local.dao.AttachmentDao
import org.libremail.data.local.dao.BackfillProgressDao
import org.libremail.data.local.dao.DraftDao
import org.libremail.data.local.dao.FolderDao
import org.libremail.data.local.dao.MessageDao
import org.libremail.data.local.dao.OutboxDao
import org.libremail.data.local.entity.AttachmentEntity
import org.libremail.data.local.entity.BackfillProgressEntity
import org.libremail.data.local.entity.DraftEntity
import org.libremail.data.local.entity.FolderEntity
import org.libremail.data.local.entity.MessageEntity
import org.libremail.data.local.entity.OutboxEntity

/**
 * The offline mail cache. Everything here is re-derivable from the server on a fresh sync, so it is
 * the database that opt-in SQLCipher encryption is applied to and — when the auth-bound key is
 * invalidated — the one that "clear + re-sync" wipes.
 *
 * Account identity and user configuration (accounts, credentials, per-account settings, signatures)
 * are deliberately NOT here: they live in [AccountDatabase], a separate non-auth-bound file, so a
 * cache-key invalidation can never sign the user out (issue #111). [MIGRATION_15_16] dropped those
 * tables from this database; [AccountDataMigrator] copies existing rows into [AccountDatabase] first.
 */
@Database(
    entities = [
        MessageEntity::class,
        AttachmentEntity::class,
        OutboxEntity::class,
        DraftEntity::class,
        FolderEntity::class,
        BackfillProgressEntity::class,
    ],
    version = 17,
    exportSchema = true,
)
abstract class LibreMailDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun outboxDao(): OutboxDao
    abstract fun draftDao(): DraftDao
    abstract fun folderDao(): FolderDao
    abstract fun backfillProgressDao(): BackfillProgressDao
}
