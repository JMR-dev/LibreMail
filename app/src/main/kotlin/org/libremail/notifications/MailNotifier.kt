// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.libremail.MainActivity
import org.libremail.R
import org.libremail.data.local.entity.MessageEntity

/** Posts on-device new-mail notifications (no push service involved). */
@Singleton
class MailNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // Permission is checked via hasPermission() below; lint can't trace the indirect guard.
    @SuppressLint("MissingPermission")
    fun notifyNewMail(messages: List<MessageEntity>) {
        if (messages.isEmpty() || !hasPermission()) return
        ensureChannel()
        val manager = NotificationManagerCompat.from(context)
        val contentIntent = contentIntent()

        // One notification per message, keyed by a stable id, so a later batch never overwrites an
        // earlier, still-unacknowledged one. setOnlyAlertOnce avoids re-buzzing for the same message.
        messages.forEach { message ->
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(message.sender)
                .setContentText(message.subject)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message.subject))
                .setCategory(NotificationCompat.CATEGORY_EMAIL)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setGroup(GROUP_KEY)
                .setContentIntent(contentIntent)
                .build()
            manager.notify(notificationId(message.id), notification)
        }

        // Group summary (the system shows it only once two or more children are present).
        val summary = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.notif_channel_new_mail))
            .setStyle(
                NotificationCompat.InboxStyle().also { style ->
                    messages.take(SUMMARY_LINES).forEach { style.addLine("${it.sender}: ${it.subject}") }
                },
            )
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setContentIntent(contentIntent)
            .build()
        manager.notify(SUMMARY_ID, summary)
    }

    private fun contentIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** Stable per-message id distinct from the summary id, so each message gets its own notification. */
    private fun notificationId(messageId: String): Int {
        val hash = messageId.hashCode()
        return if (hash == SUMMARY_ID) hash + 1 else hash
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_new_mail),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            // Redact sender/subject on a secure lock screen (the system shows a generic placeholder
            // instead). Applies on fresh installs; Android ignores channel changes after creation.
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "new_mail"
        const val GROUP_KEY = "org.libremail.NEW_MAIL"
        const val SUMMARY_ID = 1001
        const val SUMMARY_LINES = 5
    }
}
