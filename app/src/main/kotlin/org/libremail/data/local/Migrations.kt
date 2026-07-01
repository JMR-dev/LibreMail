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

/**
 * v7 -> v8: folder-aware mail (preserves existing data).
 *  - `messages`: add a `folder` column (existing rows are inbox rows). The first post-upgrade INBOX
 *    sync reconciles ids, which now embed the folder ("accountId:folder:uid").
 *  - add the `folders` table caching each account's IMAP folder list for the navigation drawer.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `messages` ADD COLUMN `folder` TEXT NOT NULL DEFAULT 'INBOX'")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `folders` (" +
                "`accountId` TEXT NOT NULL, `fullName` TEXT NOT NULL, `displayName` TEXT NOT NULL, " +
                "`role` TEXT NOT NULL, `selectable` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, " +
                "PRIMARY KEY(`accountId`, `fullName`))",
        )
    }
}

/**
 * v8 -> v9: per-account settings (preserves existing data). Adds the `account_settings` table with a
 * cascading foreign key to `accounts`, and backfills a default row for every existing account.
 *
 * Columns are declared without SQL DEFAULTs and the backfill lists every column explicitly (the
 * MIGRATION_4_5/5_6 pattern), so the fresh-install schema matches the migrated one — avoiding the
 * `@ColumnInfo(defaultValue)` mismatch that MIGRATION_6_7 had to repair.
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `account_settings` (" +
                "`accountId` TEXT NOT NULL, `signature` TEXT NOT NULL, " +
                "`signatureEnabled` INTEGER NOT NULL, `notificationsEnabled` INTEGER NOT NULL, " +
                "PRIMARY KEY(`accountId`), " +
                "FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        db.execSQL(
            "INSERT INTO `account_settings` " +
                "(`accountId`, `signature`, `signatureEnabled`, `notificationsEnabled`) " +
                "SELECT `id`, '', 1, 1 FROM `accounts`",
        )
    }
}

/**
 * v9 -> v10: bcc support for outgoing mail (preserves existing data). Adds a `bccAddresses` column
 * to `outbox` and `drafts` so a `mailto:`-launched (or manually addressed) blind-copy recipient
 * survives being queued and saved. The `DEFAULT ''` matches the entities' `@ColumnInfo(defaultValue)`
 * so the fresh-install schema validates identically to the migrated one (the MIGRATION_7_8 pattern).
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `outbox` ADD COLUMN `bccAddresses` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `drafts` ADD COLUMN `bccAddresses` TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * v10 -> v11: rich composition (preserves existing data).
 *  - `drafts`/`outbox`: add a nullable `bodyHtml` column carrying the HTML form of the body when a
 *    message was composed with formatting (null = plaintext-only, sent/kept exactly as before).
 *  - add the `signatures` table (multiple named signatures per account, one default), with a
 *    cascading foreign key to `accounts`, and backfill each account's existing per-account settings
 *    signature as its default signature so nobody loses one on upgrade.
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `drafts` ADD COLUMN `bodyHtml` TEXT")
        db.execSQL("ALTER TABLE `outbox` ADD COLUMN `bodyHtml` TEXT")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `signatures` (" +
                "`id` TEXT NOT NULL, `accountId` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`contentHtml` TEXT NOT NULL, `isDefault` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_signatures_accountId` ON `signatures` (`accountId`)")
        // Preserve any existing plain-text per-account signature as that account's default signature.
        // Newlines become <br> so the HTML keeps the original line breaks; other characters are rare
        // in signatures and pass through unescaped.
        db.execSQL(
            "INSERT INTO `signatures` (`id`, `accountId`, `name`, `contentHtml`, `isDefault`) " +
                "SELECT `accountId` || ':default-signature', `accountId`, 'Signature', " +
                "replace(`signature`, char(10), '<br>'), 1 " +
                "FROM `account_settings` WHERE `signature` <> ''",
        )
    }
}

/**
 * v11 -> v12: drawer folder de-duplication (preserves existing data). Adds a `specialUse` column to
 * `folders` recording whether the server advertises the folder as special-use (RFC 6154), so the
 * drawer can tell a provider's built-in folder from a same-named user folder. `DEFAULT 0` matches
 * the entity's `@ColumnInfo(defaultValue = "0")` so fresh-install and migrated schemas validate
 * identically (the MIGRATION_9_10 pattern); the next folder refresh backfills the real value.
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `folders` ADD COLUMN `specialUse` INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v12 -> v13: full-history backfill + device-only retention (issues #12/#13; preserves existing data).
 *  - `messages`: add the materialized `uid` column (`DEFAULT 0`, matching the entity's
 *    `@ColumnInfo(defaultValue = "0")`) and backfill it from the numeric tail of the existing
 *    "accountId:folder:uid" id. `rtrim(id, '0123456789')` strips the trailing digits, leaving the
 *    prefix up to and including the final ':'; the remainder is the UID. Non-numeric tails cast to 0
 *    and are refreshed to the real UID on the next sync.
 *  - `account_settings`: add nullable `retentionCount` / `retentionMonths` overrides (NULL = inherit
 *    the global default), declared without SQL defaults to match the entity's nullable columns.
 *  - add the `backfill_progress` table tracking each folder's paging boundary so the backfill resumes
 *    after process death / network loss.
 */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `messages` ADD COLUMN `uid` INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "UPDATE `messages` SET `uid` = " +
                "CAST(substr(`id`, length(rtrim(`id`, '0123456789')) + 1) AS INTEGER)",
        )

        db.execSQL("ALTER TABLE `account_settings` ADD COLUMN `retentionCount` INTEGER")
        db.execSQL("ALTER TABLE `account_settings` ADD COLUMN `retentionMonths` INTEGER")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `backfill_progress` (" +
                "`accountId` TEXT NOT NULL, `folder` TEXT NOT NULL, " +
                "`nextBeforeUid` INTEGER NOT NULL, `complete` INTEGER NOT NULL, " +
                "PRIMARY KEY(`accountId`, `folder`))",
        )
    }
}
