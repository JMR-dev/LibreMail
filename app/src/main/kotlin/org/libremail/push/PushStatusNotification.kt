// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.libremail.R
import org.libremail.data.sync.PushMode

/**
 * Builds the persistent foreground notification for [IdleService]. Extracted so the push-mode → text
 * choice and the channel definition — the only non-lifecycle logic the service carries — can be
 * verified with a real `Context` off the service (see `PushStatusNotificationInstrumentedTest`),
 * without standing up the foreground service, its Hilt graph, or live IMAP connections. Behaviour is
 * identical to the inline builder it replaced (issue #257).
 */
internal object PushStatusNotification {

    const val CHANNEL_ID = "push_status"
    const val FOREGROUND_ID = 1002

    /** Creates (idempotently) the low-importance channel the status notification posts on. */
    fun ensureChannel(context: Context) {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_push_status),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    /**
     * The ongoing status notification. Its text tells the truth per [mode]: "connected for instant
     * delivery" versus the low-battery 15-minute polling fallback (#90).
     */
    fun build(context: Context, mode: PushMode): Notification {
        val text = if (mode == PushMode.POLLING) {
            context.getString(R.string.notif_push_status_text_low_battery)
        } else {
            context.getString(R.string.notif_push_status_text)
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.notif_push_status_title))
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
