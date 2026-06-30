// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.domain.model

/** Everything needed for a single SMTP send attempt (transient; not persisted). */
data class SmtpParams(
    val host: String,
    val port: Int,
    val security: MailSecurity,
    val username: String,
    /** Password, app-password, or — when [useXoauth2] is true — an OAuth access token. */
    val secret: String,
    val useXoauth2: Boolean,
    /** When true (default), a STARTTLS upgrade must succeed; when false it is best-effort. */
    val strictStartTls: Boolean = true,
)
