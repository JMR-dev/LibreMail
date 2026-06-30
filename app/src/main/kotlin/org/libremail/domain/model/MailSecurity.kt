// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.domain.model

/** Transport security for an IMAP/SMTP connection. */
enum class MailSecurity {
    /** Implicit TLS (e.g. IMAPS 993 / SMTPS 465). */
    SSL_TLS,

    /** Upgrade a plaintext connection with STARTTLS (e.g. 143 / 587). */
    STARTTLS,

    /** No transport security — only for local test servers. */
    NONE,
}
