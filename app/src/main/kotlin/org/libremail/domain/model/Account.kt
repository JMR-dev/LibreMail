// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.domain.model

/** How an account authenticates with its mail server. */
enum class AuthType {
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
        private const val OUTLOOK_IMAP_PORT = 993
        private const val OUTLOOK_SMTP_PORT = 587

        /**
         * An Outlook/Microsoft account using the unified office365 endpoints (personal + M365). The id
         * lowercases the whole address ([normalizeEmailForAccountId] with `lowercaseLocalPart = true`)
         * so a re-auth with differently-cased casing resolves to the same account (issue #305).
         */
        fun outlook(email: String, displayName: String = email): Account = Account(
            id = "outlook:${normalizeEmailForAccountId(email, lowercaseLocalPart = true)}",
            email = email,
            displayName = displayName.ifBlank { email },
            authType = AuthType.OAUTH_OUTLOOK,
            imap = ServerConfig("outlook.office365.com", OUTLOOK_IMAP_PORT, MailSecurity.SSL_TLS),
            smtp = ServerConfig("smtp.office365.com", OUTLOOK_SMTP_PORT, MailSecurity.STARTTLS),
        )
    }
}

/**
 * Normalizes [email] for use in an account id (issue #305): a mailbox is one identity regardless of
 * how the address is cased, and the id is the primary key. The domain is always lowercased — mail
 * domains are case-insensitive — so `user@Gmail.com` and `user@gmail.com` never spawn two accounts
 * syncing one mailbox. The local part is lowercased only when [lowercaseLocalPart] is true: the major
 * consumer providers (Gmail, Yahoo, iCloud, AOL, Outlook) treat the whole address case-insensitively,
 * but an arbitrary manually-configured IMAP server's local part MAY be case-sensitive (RFC 5321
 * §2.3.11), so a generic account normalizes only the domain. Surrounding whitespace is trimmed either
 * way. An address with no `@` has no domain to isolate, so it is lowercased whole (consumer) or left
 * as-is (generic).
 */
fun normalizeEmailForAccountId(email: String, lowercaseLocalPart: Boolean): String {
    val trimmed = email.trim()
    val at = trimmed.lastIndexOf('@')
    if (at < 0) return if (lowercaseLocalPart) trimmed.lowercase() else trimmed
    val local = trimmed.substring(0, at)
    val domain = trimmed.substring(at + 1).lowercase()
    return "${if (lowercaseLocalPart) local.lowercase() else local}@$domain"
}
