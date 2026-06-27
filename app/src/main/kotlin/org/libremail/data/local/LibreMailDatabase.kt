// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import org.libremail.data.local.dao.AccountDao
import org.libremail.data.local.dao.AttachmentDao
import org.libremail.data.local.dao.CredentialDao
import org.libremail.data.local.dao.DraftDao
import org.libremail.data.local.dao.MessageDao
import org.libremail.data.local.dao.OutboxDao
import org.libremail.data.local.entity.AccountEntity
import org.libremail.data.local.entity.AttachmentEntity
import org.libremail.data.local.entity.CredentialEntity
import org.libremail.data.local.entity.DraftEntity
import org.libremail.data.local.entity.MessageEntity
import org.libremail.data.local.entity.OutboxEntity

@Database(
    entities = [
        AccountEntity::class,
        MessageEntity::class,
        CredentialEntity::class,
        AttachmentEntity::class,
        OutboxEntity::class,
        DraftEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class LibreMailDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun accountDao(): AccountDao
    abstract fun credentialDao(): CredentialDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun outboxDao(): OutboxDao
    abstract fun draftDao(): DraftDao
}
