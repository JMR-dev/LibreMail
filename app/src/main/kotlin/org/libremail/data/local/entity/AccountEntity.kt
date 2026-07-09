// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val id: String,
    val email: String,
    val displayName: String,
    /** Persisted name of [org.libremail.domain.model.AuthType]. */
    val authType: String,
    @Embedded(prefix = "imap_") val imap: ServerConfigEmbedded,
    @Embedded(prefix = "smtp_") val smtp: ServerConfigEmbedded,
    /**
     * The account's position in the user-defined ordering shown across the app (issue #164): the
     * Settings list, the drawer's account switcher, and the unified-inbox filter chips all list
     * accounts `ORDER BY sortOrder`. New accounts are appended (current max + 1); the user can drag to
     * reorder. `DEFAULT 0` matches [ACCOUNT_MIGRATION_1_2] so a fresh install validates identically to
     * a migrated one (the folders `specialUse` pattern).
     */
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0,
    /**
     * A user-facing sync/auth error that has halted this account, or null when healthy (issue #362). Set
     * to the "remove and re-add" message once the proactive auth circuit **latches** — the account's login
     * has failed enough consecutive times that a credential fix, not a retry, is required — so the account
     * list and the mailbox banner can surface it. Nullable with an implicit NULL default, so existing rows
     * migrate to "no error" and a fresh add (which rewrites the row) clears it.
     */
    val authError: String? = null,
)

/** Embedded host/port/security columns (prefixed per server in [AccountEntity]). */
data class ServerConfigEmbedded(
    val host: String,
    val port: Int,
    /** Persisted name of [org.libremail.domain.model.MailSecurity]. */
    val security: String,
)
