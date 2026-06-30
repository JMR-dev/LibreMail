// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local.entity

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
)

/** Embedded host/port/security columns (prefixed per server in [AccountEntity]). */
data class ServerConfigEmbedded(
    val host: String,
    val port: Int,
    /** Persisted name of [org.libremail.domain.model.MailSecurity]. */
    val security: String,
)
