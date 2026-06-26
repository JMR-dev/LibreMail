// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import org.libremail.data.local.dao.AccountDao
import org.libremail.data.local.dao.CredentialDao
import org.libremail.data.local.dao.MessageDao
import org.libremail.data.local.entity.AccountEntity
import org.libremail.data.local.entity.CredentialEntity
import org.libremail.data.local.entity.MessageEntity

@Database(
    entities = [AccountEntity::class, MessageEntity::class, CredentialEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class LibreMailDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun accountDao(): AccountDao
    abstract fun credentialDao(): CredentialDao
}
