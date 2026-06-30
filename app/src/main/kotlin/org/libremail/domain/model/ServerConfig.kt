// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.domain.model

/** Host/port/security for one mail server (IMAP or SMTP). */
data class ServerConfig(
    val host: String,
    val port: Int,
    val security: MailSecurity,
)
