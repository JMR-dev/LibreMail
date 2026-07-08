// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail

import jakarta.mail.AuthenticationFailedException

/**
 * Classifies an IMAP authentication failure that surfaced from [ImapClient.openConnectedStore]'s
 * `store.connect` (i.e. an `AUTHENTICATE`/`LOGIN` rejection) into the one distinction the account-setup
 * UI cares about: **"IMAP access is switched off for this account"** vs any other auth failure (a wrong
 * password, an expired/invalid token, a network error, …). Only the former can be fixed by the user
 * flipping a provider-side toggle, so only it earns the actionable "turn on IMAP" prompt (issue #390);
 * everything else keeps the existing generic error.
 *
 * The heuristic is deliberately conservative — misclassifying a wrong password as "IMAP disabled" would
 * send the user down a dead end — so it fires on only two well-motivated signals (see [isImapDisabled]).
 */
object ImapAuthError {

    /**
     * True when [error] (or anything in its cause chain) indicates the account's IMAP access is
     * **disabled/not enabled**, rather than a wrong credential or other failure. Two signals, checked
     * in order:
     *
     *  1. **Explicit server text.** The failure message names IMAP as disabled/not-enabled/turned-off —
     *     e.g. Gmail's `Your account is not enabled for IMAP use`, or a `IMAP access is disabled`
     *     variant. This is provider-independent and works regardless of [usedOAuth].
     *  2. **OAuth inference.** When the connection authenticated with a **freshly obtained XOAUTH2
     *     token** ([usedOAuth] true) and the server still raised an [AuthenticationFailedException], the
     *     token itself was accepted at consent/exchange time, so an `AUTHENTICATE` rejection here almost
     *     always means IMAP is off for the mailbox — not a bad token. This is the Outlook case that
     *     motivated #390: `outlook.office365.com` returns only a generic `AUTHENTICATE failed` with no
     *     distinctive text, so the text check in (1) cannot catch it.
     *
     * A password/app-password failure ([usedOAuth] false) with no IMAP-disabled text — the ordinary
     * wrong-password case — is deliberately **not** matched, so it is never misclassified.
     */
    fun isImapDisabled(error: Throwable, usedOAuth: Boolean): Boolean {
        val chain = causeChain(error)
        if (chain.any { it.message?.let(::mentionsImapDisabled) == true }) return true
        return usedOAuth && chain.any { it is AuthenticationFailedException }
    }

    /**
     * The exception and its transitive causes, in order, guarding against a self-referential or cyclic
     * cause chain (identity-based visited check — [Throwable] does not override `equals`).
     */
    private fun causeChain(error: Throwable): List<Throwable> {
        val seen = mutableListOf<Throwable>()
        var current: Throwable? = error
        while (current != null && seen.none { it === current }) {
            seen.add(current)
            current = current.cause
        }
        return seen
    }

    /** True when [message] names IMAP as disabled/not-enabled, in any of the shapes providers use. */
    private fun mentionsImapDisabled(message: String): Boolean {
        val text = message.lowercase()
        return DISABLED_PATTERNS.any { it.containsMatchIn(text) }
    }

    /**
     * Case-insensitive shapes of "IMAP is off" seen across providers (patterns run against a lowercased
     * message). Kept broad enough to catch wording variants, but each anchors on both "imap" and an
     * explicit off/disabled/not-enabled word so an ordinary "AUTHENTICATE failed" / "Invalid
     * credentials" wrong-password message never matches.
     */
    private val DISABLED_PATTERNS = listOf(
        // "IMAP access is disabled", "IMAP is disabled", "IMAP access is not enabled", "IMAP ... turned off".
        Regex("""imap[^\n]{0,40}?(disabled|not enabled|turned off|is off)"""),
        // Reversed order, e.g. Gmail's "Your account is not enabled for IMAP use".
        Regex("""(disabled|not enabled|turned off)[^\n]{0,20}?for imap"""),
        // "please enable IMAP", Gmail's "enable your account for IMAP access".
        Regex("""enable[^\n]{0,30}?imap"""),
    )
}
