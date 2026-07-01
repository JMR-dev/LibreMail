// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
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
import org.libremail.domain.model.Account

/**
 * Posts on-device new-mail notifications (no push service involved). Each account gets its own
 * notification channel inside its own channel group, so Android exposes per-account sound/vibration/
 * importance in system settings and the shade bundles each account's mail separately.
 */
@Singleton
class MailNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // Permission is checked via hasPermission() below; lint can't trace the indirect guard.
    @SuppressLint("MissingPermission")
    fun notifyNewMail(account: Account, messages: List<MessageEntity>) {
        if (messages.isEmpty() || !hasPermission()) return
        ensureAccountChannel(account)
        val manager = NotificationManagerCompat.from(context)
        val contentIntent = contentIntent()
        val channelId = channelId(account.id)
        val groupKey = groupKey(account.id)
        val summaryId = summaryId(account.id)

        // One notification per message, keyed by a stable id, so a later batch never overwrites an
        // earlier, still-unacknowledged one. setOnlyAlertOnce avoids re-buzzing for the same message.
        messages.forEach { message ->
            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(message.sender)
                .setContentText(message.subject)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message.subject))
                .setCategory(NotificationCompat.CATEGORY_EMAIL)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setGroup(groupKey)
                .setContentIntent(contentIntent)
                .build()
            manager.notify(notificationId(message.id, summaryId), notification)
        }

        // Per-account group summary (the system shows it only once two or more children are present).
        val summary = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(account.email)
            .setStyle(
                NotificationCompat.InboxStyle().also { style ->
                    style.setSummaryText(account.email)
                    messages.take(SUMMARY_LINES).forEach { style.addLine("${it.sender}: ${it.subject}") }
                },
            )
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setGroup(groupKey)
            .setGroupSummary(true)
            .setContentIntent(contentIntent)
            .build()
        manager.notify(summaryId, summary)
    }

    /**
     * Creates the account's channel (inside a per-account group named by its email), so Android owns
     * this account's sound/vibration/importance and the app can deep-link into them. Idempotent —
     * Android ignores changes to an existing channel. Also retires the pre-per-account global channel.
     */
    fun ensureAccountChannel(account: Account) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        manager.createNotificationChannelGroup(NotificationChannelGroup(account.id, account.email))
        val channel = NotificationChannel(
            channelId(account.id),
            context.getString(R.string.notif_channel_new_mail),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            group = account.id
            // Redact sender/subject on a secure lock screen (system shows a generic placeholder).
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    /** Removes an account's channel and group — called when the account is deleted. */
    fun deleteAccountChannel(accountId: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.deleteNotificationChannel(channelId(accountId))
        manager.deleteNotificationChannelGroup(accountId)
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

    /** Stable per-message id distinct from the account's summary id, so each message gets its own. */
    private fun notificationId(messageId: String, summaryId: Int): Int {
        val hash = messageId.hashCode()
        return if (hash == summaryId) hash + 1 else hash
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        /** The single global channel used before notifications became per-account. */
        private const val LEGACY_CHANNEL_ID = "new_mail"
        private const val SUMMARY_LINES = 5

        /** This account's new-mail channel id — also used to deep-link into its system settings. */
        fun channelId(accountId: String) = "new_mail:$accountId"

        private fun groupKey(accountId: String) = "org.libremail.NEW_MAIL:$accountId"
        private fun summaryId(accountId: String) = "summary:$accountId".hashCode()
    }
}
