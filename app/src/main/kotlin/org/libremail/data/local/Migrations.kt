// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v1 -> v2: add the encrypted-credentials table (preserves existing accounts/messages). */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `credentials` (" +
                "`accountId` TEXT NOT NULL, `encryptedSecret` TEXT NOT NULL, " +
                "PRIMARY KEY(`accountId`))",
        )
    }
}

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

/**
 * v6 -> v7 (preserves existing data). Rebuilds three tables to converge on Room's canonical
 * schema and add new columns:
 *  - `messages`: drop the stray `DEFAULT 0` that MIGRATION_2_3 left on `isHtml` (so upgraded and
 *    fresh installs validate identically), and add `inInbox` (server-search hits are kept out of
 *    the inbox) and `bodyFetched` (distinguishes "not fetched yet" from "fetched, empty body").
 *  - `attachments`: add an ON DELETE CASCADE foreign key to `messages` so attachment rows can no
 *    longer be orphaned, dropping any pre-existing orphans in the process.
 *  - `drafts`: add the `attachments` column so a draft round-trips its attachments.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // messages
        db.execSQL(
            "CREATE TABLE `messages_new` (" +
                "`id` TEXT NOT NULL, `accountId` TEXT NOT NULL, `sender` TEXT NOT NULL, " +
                "`senderEmail` TEXT NOT NULL, `subject` TEXT NOT NULL, `snippet` TEXT NOT NULL, " +
                "`body` TEXT NOT NULL, `isHtml` INTEGER NOT NULL, `timestampMillis` INTEGER NOT NULL, " +
                "`isRead` INTEGER NOT NULL, `isStarred` INTEGER NOT NULL, `inInbox` INTEGER NOT NULL, " +
                "`bodyFetched` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "INSERT INTO `messages_new` " +
                "SELECT id, accountId, sender, senderEmail, subject, snippet, body, isHtml, " +
                "timestampMillis, isRead, isStarred, 1, (CASE WHEN body <> '' THEN 1 ELSE 0 END) " +
                "FROM `messages`",
        )
        db.execSQL("DROP TABLE `messages`")
        db.execSQL("ALTER TABLE `messages_new` RENAME TO `messages`")
        db.execSQL("CREATE INDEX `index_messages_accountId` ON `messages` (`accountId`)")
        db.execSQL("CREATE INDEX `index_messages_timestampMillis` ON `messages` (`timestampMillis`)")

        // attachments (now with an ON DELETE CASCADE FK; orphans are dropped by the WHERE filter)
        db.execSQL(
            "CREATE TABLE `attachments_new` (" +
                "`messageId` TEXT NOT NULL, `partIndex` INTEGER NOT NULL, `filename` TEXT NOT NULL, " +
                "`mimeType` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, " +
                "PRIMARY KEY(`messageId`, `partIndex`), " +
                "FOREIGN KEY(`messageId`) REFERENCES `messages`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        db.execSQL(
            "INSERT INTO `attachments_new` " +
                "SELECT messageId, partIndex, filename, mimeType, sizeBytes FROM `attachments` " +
                "WHERE messageId IN (SELECT id FROM `messages`)",
        )
        db.execSQL("DROP TABLE `attachments`")
        db.execSQL("ALTER TABLE `attachments_new` RENAME TO `attachments`")
        db.execSQL("CREATE INDEX `index_attachments_messageId` ON `attachments` (`messageId`)")

        // drafts
        db.execSQL(
            "CREATE TABLE `drafts_new` (" +
                "`id` TEXT NOT NULL, `accountId` TEXT, `toAddresses` TEXT NOT NULL, " +
                "`ccAddresses` TEXT NOT NULL, `subject` TEXT NOT NULL, `body` TEXT NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, `attachments` TEXT NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "INSERT INTO `drafts_new` " +
                "SELECT id, accountId, toAddresses, ccAddresses, subject, body, updatedAt, '' " +
                "FROM `drafts`",
        )
        db.execSQL("DROP TABLE `drafts`")
        db.execSQL("ALTER TABLE `drafts_new` RENAME TO `drafts`")
    }
}
