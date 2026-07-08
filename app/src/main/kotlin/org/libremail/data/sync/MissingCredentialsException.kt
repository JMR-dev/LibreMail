// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

/**
 * Thrown by [MailConnectionFactory] when an account has no stored credential to resolve.
 *
 * It extends [IllegalStateException] — the type [kotlin.error] previously raised here — so existing
 * callers that treat a missing credential as a hard failure are unaffected. What it adds is a type a
 * caller can catch *specifically*: the IMAP IDLE watcher ([org.libremail.push.IdleService]) tolerates
 * the brief account-add write race (#403), where a reactive observer of the accounts table can see a
 * newly-added account a beat before its secret has finished persisting, by catching this and deferring
 * instead of logging a connection failure. Genuinely-absent credentials still surface as an error to
 * every other caller.
 *
 * The message is deliberately PII-free (no email, host, or account id): an account id embeds the raw
 * email address, so it must never appear in an exception message that could reach Logcat. A caller that
 * needs to name the account in a log line uses [org.libremail.reporting.accountLogRef] on the id it
 * already holds.
 */
class MissingCredentialsException : IllegalStateException("No stored credentials for account")
