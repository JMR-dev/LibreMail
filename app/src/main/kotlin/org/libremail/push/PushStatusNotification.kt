// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.annotation.StringRes
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
     * The ongoing status notification. Its text tells the truth per [statusTextRes]: "connected for
     * instant delivery" versus the 15-minute polling fallback — low battery (#90) or the dataSync FGS
     * runtime-cap timeout ([timedOut], #302).
     */
    fun build(context: Context, mode: PushMode, timedOut: Boolean = false): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.notif_push_status_title))
            .setContentText(context.getString(statusTextRes(mode, timedOut)))
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    /**
     * The status-text resource for the current push state — pulled out as a pure function so the
     * "which message for which state" decision is unit-testable on the JVM. ([build] itself can only
     * be asserted in an instrumented test: the unit-test `android.jar`'s `NotificationCompat` is a
     * no-op stub — see `PushStatusNotificationInstrumentedTest`.) [timedOut] marks the Android 14+
     * dataSync FGS runtime-cap fallback (#302); like the low-battery [PushMode.POLLING] fallback (#90)
     * it drops to the 15-minute periodic sync, but for a different reason, so it gets its own text and
     * takes precedence over [mode] (the platform delivers the timeout while push is nominally IDLE).
     */
    @StringRes
    fun statusTextRes(mode: PushMode, timedOut: Boolean): Int = when {
        timedOut -> R.string.notif_push_status_text_timed_out
        mode == PushMode.POLLING -> R.string.notif_push_status_text_low_battery
        else -> R.string.notif_push_status_text
    }
}
