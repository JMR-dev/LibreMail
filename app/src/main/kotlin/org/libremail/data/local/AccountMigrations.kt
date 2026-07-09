// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migrations for [AccountDatabase] — the non-auth account store split out of the cache database in
 * issue #111. Kept in a SEPARATE file from the cache database's `MIGRATION_x_y` chain in
 * `Migrations.kt` on purpose: both databases number their own schema versions from 1, and
 * `MigrationTest` reflectively pulls every `Migration` val out of the `Migrations.kt` file facade to
 * assert the cache chain is unbroken — an AccountDatabase migration declared alongside them would
 * pollute that assertion with a second `1 -> 2`. A distinct file (and the `ACCOUNT_` prefix) keeps
 * the two chains from colliding at the package level.
 */

/**
 * AccountDatabase v1 -> v2 (issue #164): add the user-controlled [AccountEntity.sortOrder]. Existing
 * accounts are given a stable initial order matching the previous alphabetical (`ORDER BY email`)
 * listing — each account's rank among the others by email — so the order they were already shown in
 * doesn't shuffle on upgrade. `DEFAULT 0` matches the entity's `@ColumnInfo(defaultValue = "0")` so a
 * fresh install validates identically to a migrated one (the folders `specialUse` MIGRATION_11_12
 * pattern); the backfill then overwrites that default with the real rank.
 */
val ACCOUNT_MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `accounts` ADD COLUMN `sortOrder` INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "UPDATE `accounts` SET `sortOrder` = " +
                "(SELECT COUNT(*) FROM `accounts` AS ranked WHERE ranked.`email` < `accounts`.`email`)",
        )
    }
}

/**
 * AccountDatabase v2 -> v3 (issue #362): add the nullable [AccountEntity.authError], a user-facing sync/auth
 * error surfaced on the account row and as a mailbox banner. The column is nullable with no default, so
 * every existing account migrates to NULL ("no error, healthy") — matching the entity's `authError: String?
 * = null` — and the proactive auth circuit later stamps the "remove and re-add" message onto an account
 * whose Yahoo/AOL login has latched. A plain ADD COLUMN suffices; nothing is backfilled.
 */
val ACCOUNT_MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `accounts` ADD COLUMN `authError` TEXT")
    }
}
