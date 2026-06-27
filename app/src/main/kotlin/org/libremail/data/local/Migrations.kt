// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v2 -> v3: add the [isHtml] flag to cached messages (preserves existing data). */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN isHtml INTEGER NOT NULL DEFAULT 0")
    }
}

/** v3 -> v4: add the attachments metadata table (preserves existing data). */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `attachments` (" +
                "`messageId` TEXT NOT NULL, `partIndex` INTEGER NOT NULL, `filename` TEXT NOT NULL, " +
                "`mimeType` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, " +
                "PRIMARY KEY(`messageId`, `partIndex`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_attachments_messageId` ON `attachments` (`messageId`)",
        )
    }
}

/** v4 -> v5: add the outbox table for queued outgoing mail (preserves existing data). */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `outbox` (" +
                "`id` TEXT NOT NULL, `accountId` TEXT NOT NULL, `toAddresses` TEXT NOT NULL, " +
                "`ccAddresses` TEXT NOT NULL, `subject` TEXT NOT NULL, `body` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `lastError` TEXT, PRIMARY KEY(`id`))",
        )
    }
}

/** v5 -> v6: add the drafts table for saved unsent mail (preserves existing data). */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `drafts` (" +
                "`id` TEXT NOT NULL, `accountId` TEXT, `toAddresses` TEXT NOT NULL, " +
                "`ccAddresses` TEXT NOT NULL, `subject` TEXT NOT NULL, `body` TEXT NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
    }
}
