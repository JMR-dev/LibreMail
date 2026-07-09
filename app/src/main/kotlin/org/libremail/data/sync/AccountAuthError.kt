// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import android.content.Context
import org.libremail.R
import org.libremail.data.local.dao.AccountDao
import org.libremail.domain.model.Account
import org.libremail.domain.model.ImapConnectionParams
import org.libremail.mail.AuthThrottleGate
import org.libremail.reporting.AppLog
import org.libremail.reporting.accountLogRef

/** Tag for the account-auth-error reconciliation breadcrumb (issue #362). PII-free. */
private const val AUTH_ERROR_TAG = "AccountAuthError"

/**
 * Bridges the in-memory proactive auth circuit ([AuthThrottleGate]) to the **durable, user-visible account
 * error** (issue #362). When [params]'s account has *latched* its circuit — the threshold of consecutive
 * Yahoo/AOL authentication failures reached, i.e. a wrong app-password that won't fix itself — this persists
 * the "remove and re-add this account" message onto the account row (via [AccountDao.setAuthError]) so the
 * account list and the mailbox banner surface it, and returns `true` so the caller stops syncing the account
 * (fail loud and stop, rather than the old silent self-clearing backoff window).
 *
 * The write is conditional (idempotent) at the SQL level, so across the many sync/backfill slices that may
 * observe the same latch the error is stamped — and the breadcrumb logged — **exactly once**. Returns
 * `false` and does nothing for a healthy or still-ramping account, and for every non-Yahoo/AOL host (which
 * never latches). PII-free: only a hashed account ref is ever logged; the account id/email/host/credentials
 * are not.
 *
 * Shared by [MailSyncer] and [MailBackfiller] — the two loops that already hold the [Account] and an
 * [AccountDao] — rather than injecting a DAO into the mail-layer gate, keeping the persistence write in the
 * data layer where it belongs.
 */
internal suspend fun markAccountErroredIfLatched(
    authGate: AuthThrottleGate,
    accountDao: AccountDao,
    context: Context,
    account: Account,
    params: ImapConnectionParams,
): Boolean {
    if (!authGate.isAuthLatched(params)) return false
    val message = context.getString(R.string.account_auth_error_remove_readd)
    if (accountDao.setAuthError(account.id, message) > 0) {
        AppLog.w(
            AUTH_ERROR_TAG,
            "auth circuit latched ${accountLogRef(account.id)}: account marked errored, sync stopped until re-add",
        )
    }
    return true
}
