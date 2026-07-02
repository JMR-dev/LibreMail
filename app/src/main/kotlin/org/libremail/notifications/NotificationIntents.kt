// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.notifications

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.libremail.MainActivity

/**
 * Builds and parses the intent behind a tapped per-message new-mail notification, keeping both sides
 * of the contract ([MailNotifier] builds, MainActivity parses) in one place.
 *
 * The per-message `data` URI is load-bearing: PendingIntent identity ignores extras, so without a
 * distinct URI every message's notification would collapse onto one FLAG_UPDATE_CURRENT PendingIntent
 * and always open the most-recently-notified message. The intent is explicit (component set), so the
 * private scheme needs no manifest intent-filter and adds no exported surface.
 */
object NotificationIntents {

    private const val ACTION_OPEN_MESSAGE = "org.libremail.action.OPEN_MESSAGE"
    private const val EXTRA_MESSAGE_ID = "org.libremail.extra.MESSAGE_ID"

    fun openMessage(context: Context, messageId: String): Intent = Intent(context, MainActivity::class.java).apply {
        action = ACTION_OPEN_MESSAGE
        data = Uri.parse("libremail://message/${Uri.encode(messageId)}")
        putExtra(EXTRA_MESSAGE_ID, messageId)
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }

    /** The tapped message's id, or null for any other intent (launcher, mailto:, share, …). */
    fun messageId(intent: Intent?): String? =
        intent?.takeIf { it.action == ACTION_OPEN_MESSAGE }?.getStringExtra(EXTRA_MESSAGE_ID)
}
