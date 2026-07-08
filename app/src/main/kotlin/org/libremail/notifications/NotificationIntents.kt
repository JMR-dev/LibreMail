// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import org.libremail.MainActivity
import org.libremail.reporting.AppLog

/**
 * Builds and parses the intent behind a tapped per-message new-mail notification, keeping both sides
 * of the contract ([MailNotifier] builds, MainActivity parses) in one place.
 *
 * The per-message `data` URI is load-bearing: PendingIntent identity ignores extras, so without a
 * distinct URI every message's notification would collapse onto one FLAG_UPDATE_CURRENT PendingIntent
 * and always open the most-recently-notified message. The intent is explicit (component set), so the
 * private scheme needs no manifest intent-filter and adds no exported surface.
 *
 * [MainActivity] is nonetheless `exported="true"` (launcher / mailto: / share), so another app could
 * still target it with an explicit `ACTION_OPEN_MESSAGE` intent and drive the reader to an arbitrary
 * cached message id (#307). To honour only this app's own notification taps, [openMessage] attaches an
 * unforgeable **sender token** — a PendingIntent whose creator package is stamped by the system — and
 * [messageId] yields the id only when that token was created by us. A foreign caller carries no token
 * (or one attributed to its own package), so its intent is ignored.
 */
object NotificationIntents {

    private const val TAG = "NotificationIntents"
    private const val ACTION_OPEN_MESSAGE = "org.libremail.action.OPEN_MESSAGE"
    private const val ACTION_SENDER_TOKEN = "org.libremail.action.SENDER_TOKEN"
    private const val EXTRA_MESSAGE_ID = "org.libremail.extra.MESSAGE_ID"
    private const val EXTRA_SENDER_TOKEN = "org.libremail.extra.SENDER_TOKEN"

    fun openMessage(context: Context, messageId: String): Intent = Intent(context, MainActivity::class.java).apply {
        action = ACTION_OPEN_MESSAGE
        data = Uri.parse("libremail://message/${Uri.encode(messageId)}")
        putExtra(EXTRA_MESSAGE_ID, messageId)
        putExtra(EXTRA_SENDER_TOKEN, senderToken(context))
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }

    /**
     * The tapped message's id, or null for any other intent (launcher, mailto:, share, …). Also null
     * for an `ACTION_OPEN_MESSAGE` intent that did not originate from this app's own notification —
     * i.e. one whose [senderToken] is missing or was minted by another package — so a foreign caller
     * targeting the exported [MainActivity] can never drive the reader (#307).
     */
    fun messageId(context: Context, intent: Intent?): String? {
        if (intent?.action != ACTION_OPEN_MESSAGE) return null
        val token = IntentCompat.getParcelableExtra(intent, EXTRA_SENDER_TOKEN, PendingIntent::class.java)
        if (token?.creatorPackage != context.packageName) {
            AppLog.w(TAG, "Ignoring ACTION_OPEN_MESSAGE intent lacking this app's sender token")
            return null
        }
        return intent.getStringExtra(EXTRA_MESSAGE_ID)
    }

    /**
     * An immutable PendingIntent used purely as unforgeable proof of origin: only this app can mint one
     * whose [PendingIntent.getCreatorPackage] is our package. It is never sent — a package-scoped
     * broadcast to a receiver we don't register — so firing it (which never happens) is a no-op.
     */
    private fun senderToken(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(ACTION_SENDER_TOKEN).setPackage(context.packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
