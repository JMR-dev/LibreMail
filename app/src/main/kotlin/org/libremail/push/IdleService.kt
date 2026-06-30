// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.libremail.R
import org.libremail.data.local.dao.AccountDao
import org.libremail.data.local.toDomain
import org.libremail.data.sync.MailConnectionFactory
import org.libremail.data.sync.MailSyncer
import org.libremail.domain.model.Account
import org.libremail.mail.ImapClient

/**
 * Foreground service that holds a long-lived IMAP IDLE connection per account so the server can
 * push new mail to us instantly — no polling, and no third-party push service. When IDLE reports
 * activity we run a normal sync, which writes to Room and fires the new-mail notification.
 */
@AndroidEntryPoint
class IdleService : Service() {

    @Inject lateinit var accountDao: AccountDao
    @Inject lateinit var connectionFactory: MailConnectionFactory
    @Inject lateinit var imapClient: ImapClient
    @Inject lateinit var mailSyncer: MailSyncer

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watching = false

    /** Active IDLE watcher per account id, so we can start/stop them as accounts change. */
    private val watchers = mutableMapOf<String, Job>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (!watching) {
            watching = true
            scope.launch { reconcileWatchers() }
        }
        return START_STICKY
    }

    /**
     * Observes the account list and keeps one IDLE watcher per account: a watcher is started for a
     * newly-added account and cancelled when its account is removed (which promptly closes that
     * account's IDLE connection). The service is started/stopped by the app based on whether any
     * accounts exist, so reaching zero here is just a transient state.
     */
    private suspend fun reconcileWatchers() {
        accountDao.observeAll().collect { entities ->
            val accounts = entities.map { it.toDomain() }
            val currentIds = accounts.mapTo(mutableSetOf()) { it.id }
            (watchers.keys - currentIds).forEach { id -> watchers.remove(id)?.cancel() }
            accounts.forEach { account ->
                if (account.id !in watchers) {
                    watchers[account.id] = scope.launch { watchAccount(account) }
                }
            }
        }
    }

    /**
     * Holds IDLE for one account, reconnecting with exponential backoff whenever it drops.
     * Each IDLE session is bounded by [IDLE_RENEWAL_MS]: when it elapses, [withTimeoutOrNull]
     * cancels idle() (which closes the connection to unblock it) and we reconnect with a fresh
     * IDLE. This re-issues IDLE well within RFC 2177's 29-minute limit and before NAT/firewall
     * idle-socket timeouts would silently strand the connection. Each reconnect catches up via
     * idle()'s on-connect sync, so no mail is missed across renewals.
     */
    private suspend fun watchAccount(account: Account) {
        var backoffMs = INITIAL_BACKOFF_MS
        while (scope.isActive) {
            try {
                val params = connectionFactory.imapParamsFor(account)
                withTimeoutOrNull(IDLE_RENEWAL_MS) {
                    // Sync just this account on its own push — not every account.
                    imapClient.idle(params) { mailSyncer.syncAccount(account.id) }
                }
                backoffMs = INITIAL_BACKOFF_MS
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground() {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_push_status),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.notif_push_status_title))
            .setContentText(getString(R.string.notif_push_status_text))
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        ServiceCompat.startForeground(
            this,
            FOREGROUND_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private companion object {
        const val CHANNEL_ID = "push_status"
        const val FOREGROUND_ID = 1002
        const val INITIAL_BACKOFF_MS = 5_000L
        const val MAX_BACKOFF_MS = 5 * 60_000L

        // Re-establish IDLE on this cadence — under RFC 2177's 29-minute ceiling and short enough
        // to beat typical NAT/firewall idle-socket timeouts.
        const val IDLE_RENEWAL_MS = 9 * 60_000L
    }
}
