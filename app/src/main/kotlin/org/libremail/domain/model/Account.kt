// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.domain.model

/** How an account authenticates with its mail server. */
enum class AuthType {
    /** Gmail via OAuth 2.0 (XOAUTH2 over IMAP/SMTP). */
    OAUTH_GMAIL,

    /** Generic IMAP/SMTP with a password or app-password. */
    PASSWORD_IMAP,
}

data class Account(
    val id: String,
    val email: String,
    val displayName: String,
    val authType: AuthType,
)
