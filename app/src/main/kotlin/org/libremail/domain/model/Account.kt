// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.domain.model

/** How an account authenticates with its mail server. */
enum class AuthType {
    /** Gmail via OAuth 2.0 (XOAUTH2 over IMAP/SMTP). */
    OAUTH_GMAIL,

    /** Outlook / Microsoft via OAuth 2.0 (XOAUTH2 over IMAP/SMTP). */
    OAUTH_OUTLOOK,

    /** Generic IMAP/SMTP with a password or app-password. */
    PASSWORD_IMAP,
}

data class Account(
    val id: String,
    val email: String,
    val displayName: String,
    val authType: AuthType,
    val imap: ServerConfig,
    val smtp: ServerConfig,
) {
    companion object {
        /** A Gmail account with Google's standard IMAP/SMTP endpoints. */
        fun gmail(email: String, displayName: String = email): Account = Account(
            id = "gmail:$email",
            email = email,
            displayName = displayName.ifBlank { email },
            authType = AuthType.OAUTH_GMAIL,
            imap = ServerConfig("imap.gmail.com", 993, MailSecurity.SSL_TLS),
            smtp = ServerConfig("smtp.gmail.com", 465, MailSecurity.SSL_TLS),
        )

        /** An Outlook/Microsoft account using the unified office365 endpoints (personal + M365). */
        fun outlook(email: String, displayName: String = email): Account = Account(
            id = "outlook:$email",
            email = email,
            displayName = displayName.ifBlank { email },
            authType = AuthType.OAUTH_OUTLOOK,
            imap = ServerConfig("outlook.office365.com", 993, MailSecurity.SSL_TLS),
            smtp = ServerConfig("smtp.office365.com", 587, MailSecurity.STARTTLS),
        )
    }
}
