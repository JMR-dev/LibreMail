// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.domain.model

// Standard mail submission ports, named so the presets below don't read as "magic numbers".
// File-level (not in the companion) so the enum entries can reference them during construction.
private const val IMAPS_PORT = 993
private const val SMTP_SUBMISSION_PORT = 587
private const val SMTPS_PORT = 465

/**
 * Preconfigured IMAP/SMTP presets for the app-password vendors (Gmail, Yahoo, iCloud).
 *
 * These mirror [Account.outlook]: each entry knows its servers so onboarding only has to collect an
 * email + app password (see the app-password setup screen). Outlook is intentionally NOT here — it
 * uses interactive OAuth, not an app password.
 *
 * Port / security rationale (verified against current vendor docs):
 *  - IMAP is implicit TLS on 993 for all three: none of these vendors document a STARTTLS IMAP
 *    endpoint, so 993/[MailSecurity.SSL_TLS] is the only correct choice.
 *  - SMTP biases toward STARTTLS on 587 where the vendor documents it (Gmail, iCloud), matching the
 *    epic's "prefer STARTTLS where supported" guidance. Yahoo documents implicit TLS on 465 as its
 *    outgoing server, so it keeps 465/[MailSecurity.SSL_TLS].
 *  - [MailSecurity.NONE] is never used — every path here is encrypted end to end.
 */
enum class MailProvider(
    /** Stable lowercase key used as a navigation argument and to look a provider back up. */
    val key: String,
    /** Brand name shown in the picker and setup screen (a proper noun, not localized). */
    val displayName: String,
    /** The page where the user creates an app password for this provider. */
    val appPasswordHelpUrl: String,
    /**
     * Setup instructions for the provider's two-factor prerequisite, or null when there isn't one.
     * Only Gmail refuses to create app passwords until 2-Step Verification is on, so only Gmail
     * links its setup article; Yahoo and iCloud gate nothing on it.
     */
    val twoFactorHelpUrl: String? = null,
    private val imapHost: String,
    private val smtpHost: String,
    private val smtpPort: Int,
    private val smtpSecurity: MailSecurity,
    /**
     * Extra IMAP hosts that also identify this provider, besides [imapHost] — e.g. Gmail's legacy
     * `imap.googlemail.com`. Matched case-insensitively by [matchesHost] so a manually configured
     * account on a legacy host still resolves to the right brand.
     */
    private val hostAliases: List<String> = emptyList(),
) {
    GMAIL(
        key = "gmail",
        displayName = "Gmail",
        appPasswordHelpUrl = "https://myaccount.google.com/apppasswords",
        // Google's "Turn on 2-Step Verification" article — the app-passwords page above bounces
        // accounts that haven't enabled it yet, so the setup screen offers this as a way out.
        twoFactorHelpUrl = "https://support.google.com/accounts/answer/185839",
        imapHost = "imap.gmail.com",
        smtpHost = "smtp.gmail.com",
        // Google documents smtp.gmail.com:587 with STARTTLS as the standard submission endpoint.
        smtpPort = SMTP_SUBMISSION_PORT,
        smtpSecurity = MailSecurity.STARTTLS,
        // Legacy Gmail IMAP host still seen on older, manually configured accounts.
        hostAliases = listOf("imap.googlemail.com"),
    ),
    YAHOO(
        key = "yahoo",
        displayName = "Yahoo Mail",
        appPasswordHelpUrl = "https://login.yahoo.com/account/security",
        imapHost = "imap.mail.yahoo.com",
        smtpHost = "smtp.mail.yahoo.com",
        // Yahoo documents smtp.mail.yahoo.com:465 with implicit SSL/TLS as its outgoing server.
        smtpPort = SMTPS_PORT,
        smtpSecurity = MailSecurity.SSL_TLS,
    ),
    ICLOUD(
        key = "icloud",
        displayName = "iCloud Mail",
        appPasswordHelpUrl = "https://appleid.apple.com",
        imapHost = "imap.mail.me.com",
        smtpHost = "smtp.mail.me.com",
        // Apple documents smtp.mail.me.com:587 with STARTTLS for iCloud Mail.
        smtpPort = SMTP_SUBMISSION_PORT,
        smtpSecurity = MailSecurity.STARTTLS,
    ),
    ;

    /**
     * Builds a [PASSWORD_IMAP][AuthType.PASSWORD_IMAP] [Account] for this provider. The caller
     * supplies the address; the servers come from the preset. The id mirrors the manual-setup
     * convention (`imap:<email>`) so app-password and manual accounts share one identity scheme.
     */
    fun createAccount(email: String, displayName: String = email): Account {
        val address = email.trim()
        return Account(
            id = "imap:$address",
            email = address,
            displayName = displayName.trim().ifBlank { address },
            authType = AuthType.PASSWORD_IMAP,
            imap = ServerConfig(imapHost, IMAPS_PORT, MailSecurity.SSL_TLS),
            smtp = ServerConfig(smtpHost, smtpPort, smtpSecurity),
        )
    }

    /**
     * True when [host] is this provider's [imapHost] or one of its [hostAliases] (case-insensitive).
     * The single place that knows which IMAP hosts belong to this provider.
     */
    fun matchesHost(host: String): Boolean =
        imapHost.equals(host, ignoreCase = true) || hostAliases.any { it.equals(host, ignoreCase = true) }

    companion object {
        /** The folder-label brand shown for Microsoft Outlook / Office 365 accounts. */
        const val OUTLOOK_BRAND = "Outlook"

        /** Resolves a provider by its [key], or null if none matches (case-insensitive). */
        fun fromKey(key: String): MailProvider? = entries.firstOrNull { it.key.equals(key, ignoreCase = true) }

        /** Resolves an app-password provider by its IMAP host (or alias), or null if none matches. */
        fun forImapHost(host: String): MailProvider? = entries.firstOrNull { it.matchesHost(host) }

        /**
         * The recognized brand name for [account], or null when its host maps to no known brand. The
         * single source of truth for host→brand matching: Outlook is identified by its OAuth auth
         * type or a Microsoft mail host (it is deliberately not an app-password [MailProvider] entry,
         * as it uses interactive OAuth), and every other brand resolves through [forImapHost].
         */
        fun brandFor(account: Account): String? = when {
            account.authType == AuthType.OAUTH_OUTLOOK -> OUTLOOK_BRAND
            isOutlookHost(account.imap.host) -> OUTLOOK_BRAND
            else -> forImapHost(account.imap.host)?.displayName
        }

        /**
         * True for Microsoft's Outlook / Office 365 mail hosts (e.g. `outlook.office365.com`). Matches
         * the `office365.com` domain and `outlook.office.com` precisely, rather than any host merely
         * containing "outlook", so an unrelated host can't be mislabeled as Outlook.
         */
        private fun isOutlookHost(host: String): Boolean {
            val normalized = host.lowercase()
            return normalized == "office365.com" ||
                normalized.endsWith(".office365.com") ||
                normalized == "outlook.office.com"
        }
    }
}
