// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.libremail.R
import org.libremail.data.local.dao.AccountDao
import org.libremail.data.local.toDomain
import org.libremail.data.security.EncryptedCacheGuard
import org.libremail.data.sync.MailConnectionFactory
import org.libremail.data.sync.MailSyncer
import org.libremail.data.sync.PushMode
import org.libremail.data.sync.SyncResourcePolicy
import org.libremail.data.sync.SyncScheduler
import org.libremail.domain.model.Account
import org.libremail.mail.ImapClient
import org.libremail.power.BatteryStatusProvider
import org.libremail.reporting.AppLog
import javax.inject.Inject

/**
 * Foreground service that holds a long-lived IMAP IDLE connection per account so the server can
 * push new mail to us instantly — no polling, and no third-party push service. When IDLE reports
 * activity we run a normal sync, which writes to Room and fires the new-mail notification.
 *
 * At low battery (#90) the service stays up but proactively closes every IDLE connection and drops
 * to [PushMode.POLLING]: mail then arrives via the always-scheduled 15-minute periodic sync, and the
 * persistent notification says so instead of pretending push is live. Once battery recovers (with
 * hysteresis — see [SyncResourcePolicy.pushMode]) or the device is plugged in, IDLE resumes and its
 * on-connect sync catches up anything that arrived in between. A deliberate, visible fallback beats
 * the previous emergent one, where the OS throttled the low-battery socket and the reconnect loop
 * burned battery retrying while the notification still claimed instant delivery.
 */
@AndroidEntryPoint
class IdleService : Service() {

    // Lazy: AccountDao / MailConnectionFactory (via CredentialStore) / MailSyncer all resolve the Room
    // DB, which blocks while the encrypted cache is locked. Resolve them only after the cache-lock
    // check in onStartCommand, so a start into a still-locked process defers instead of ANR-ing.
    @Inject lateinit var accountDao: Lazy<AccountDao>

    @Inject lateinit var connectionFactory: Lazy<MailConnectionFactory>

    @Inject lateinit var imapClient: ImapClient

    @Inject lateinit var mailSyncer: Lazy<MailSyncer>

    @Inject lateinit var cacheGuard: EncryptedCacheGuard

    @Inject lateinit var batteryStatusProvider: BatteryStatusProvider

    @Inject lateinit var syncScheduler: SyncScheduler

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watching = false

    /** The push mode the foreground notification currently reflects. */
    @Volatile
    private var shownMode = PushMode.IDLE

    /** Active IDLE watcher per account id, so we can start/stop them as accounts change. */
    private val watchers = mutableMapOf<String, Job>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground(shownMode)
        if (!watching) {
            watching = true
            scope.launch {
                // Can't open the encrypted DB without the user present. Defer (stop) and let the app
                // restart push after the next unlock, rather than block the service and ANR.
                if (cacheGuard.isCacheLocked()) {
                    Log.i(TAG, "encrypted cache locked; deferring IDLE push until the app is unlocked")
                    stopSelf()
                    return@launch
                }
                reconcileWatchers()
            }
        }
        return START_STICKY
    }

    /**
     * Observes the account list and the battery-derived [PushMode], and keeps one IDLE watcher per
     * account while in [PushMode.IDLE]: a watcher is started for a newly-added account and cancelled
     * when its account is removed (which promptly closes that account's IDLE connection). In
     * [PushMode.POLLING] no watcher is wanted, so entering it cancels them all — cleanly closing the
     * IDLE connections — and leaving it starts them again. The service is started/stopped by the app
     * based on whether any accounts exist, so reaching zero here is just a transient state.
     */
    private suspend fun reconcileWatchers() {
        val pushModes = SyncResourcePolicy.pushModes(batteryStatusProvider.current(), batteryStatusProvider.status())
        combine(accountDao.get().observeAll(), pushModes) { entities, mode ->
            entities.map { it.toDomain() } to mode
        }.collect { (accounts, mode) ->
            if (mode != shownMode) {
                shownMode = mode
                onPushModeChanged(mode)
            }
            val wanted = if (mode == PushMode.POLLING) emptyList() else accounts
            val wantedIds = wanted.mapTo(mutableSetOf()) { it.id }
            (watchers.keys - wantedIds).forEach { id -> watchers.remove(id)?.cancel() }
            wanted.forEach { account ->
                if (account.id !in watchers) {
                    watchers[account.id] = scope.launch { watchAccount(account) }
                }
            }
        }
    }

    /** Makes a push-mode change visible (#90): updates the persistent notification and the app log. */
    private fun onPushModeChanged(mode: PushMode) {
        startAsForeground(mode)
        if (mode == PushMode.POLLING) {
            AppLog.i(TAG, "Battery low: pausing IMAP IDLE; mail arrives via 15-minute periodic sync")
            // The periodic fallback is scheduled at every app start with KEEP, so this is normally a
            // no-op — re-asserted here so the fallback provably exists whenever push is paused.
            syncScheduler.schedulePeriodicSync()
        } else {
            AppLog.i(TAG, "Battery recovered: resuming IMAP IDLE push")
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
                val params = connectionFactory.get().imapParamsFor(account)
                withTimeoutOrNull(IDLE_RENEWAL_MS) {
                    // Sync just this account on its own push — not every account.
                    imapClient.idle(params) { mailSyncer.get().syncAccount(account.id) }
                }
                backoffMs = INITIAL_BACKOFF_MS
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "IDLE for ${account.email} dropped; retrying in ${backoffMs}ms", e)
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

    /**
     * Starts (or, on later calls, updates) the foreground notification. The text tells the truth per
     * [mode]: "connected for instant delivery" versus the low-battery 15-minute polling fallback.
     */
    private fun startAsForeground(mode: PushMode) {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_push_status),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val text = if (mode == PushMode.POLLING) {
            getString(R.string.notif_push_status_text_low_battery)
        } else {
            getString(R.string.notif_push_status_text)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.notif_push_status_title))
            .setContentText(text)
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
        const val TAG = "IdleService"
        const val CHANNEL_ID = "push_status"
        const val FOREGROUND_ID = 1002
        const val INITIAL_BACKOFF_MS = 5_000L
        const val MAX_BACKOFF_MS = 5 * 60_000L

        // Re-establish IDLE on this cadence — under RFC 2177's 29-minute ceiling and short enough
        // to beat typical NAT/firewall idle-socket timeouts.
        const val IDLE_RENEWAL_MS = 9 * 60_000L
    }
}
