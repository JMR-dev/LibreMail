// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.push

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
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
import org.libremail.reporting.accountLogRef
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
                    AppLog.i(TAG, "encrypted cache locked; deferring IDLE push until the app is unlocked")
                    stopSelf()
                    return@launch
                }
                reconcileWatchers()
            }
            // The reuse cache (issue #357 Part 2) keeps interactive/sync IMAP connections warm; sweep
            // them so a socket that has gone idle past the reuse timeout is closed rather than left
            // draining battery. Independent of push mode — it runs while the service lives.
            scope.launch { evictIdleReuseConnectionsLoop() }
        }
        return START_STICKY
    }

    /**
     * Periodically evicts IMAP connections the reuse cache kept warm once they go idle past the reuse
     * idle timeout (issue #357 Part 2). A no-op when reuse is disabled or nothing is idle;
     * [ImapClient.evictIdleReusedConnections] skips any connection currently in use, so a sweep never
     * disturbs an in-flight sync or interactive fetch.
     */
    private suspend fun evictIdleReuseConnectionsLoop() {
        while (scope.isActive) {
            delay(REUSE_EVICTION_SWEEP_MS)
            runCatching { imapClient.evictIdleReusedConnections() }
                .onFailure { AppLog.w(TAG, "reuse idle-eviction sweep failed", it) }
        }
    }

    /**
     * Android 14+'s runtime cap on a `dataSync` foreground service (~6h per rolling 24h window) fires
     * this callback and then force-stops the service — throwing a system FGS-timeout exception — if we
     * don't stop it ourselves (issue #302). So drop out of foreground state cleanly and fall back to
     * the always-scheduled 15-minute periodic sync, exactly like the low-battery [PushMode.POLLING]
     * path, leaving the persistent notification saying so. The platform delivers the timeout to this
     * deprecated single-arg form on API 34 and to the [onTimeout] overload carrying the fgsType on
     * API 35+; both route to the same clean shutdown, and the handler is idempotent.
     */
    @Deprecated("Platform calls onTimeout(startId, fgsType) on API 35+; both overloads route here.")
    override fun onTimeout(startId: Int) = fallBackToPeriodicSync()

    override fun onTimeout(startId: Int, fgsType: Int) = fallBackToPeriodicSync()

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
            // The periodic fallback is already scheduled at every app start (UPDATE), so re-asserting it
            // here is effectively a no-op — done anyway so the fallback provably exists whenever push is
            // paused, without disturbing the running period.
            syncScheduler.schedulePeriodicSync()
            // Mirror the IDLE teardown for the reuse cache (issue #357 Part 2): drop any warm
            // interactive/sync connections so we hold no kept-alive IMAP sockets while conserving
            // battery. They re-establish on the next sync/interactive op once battery recovers.
            scope.launch { imapClient.closeReusedConnections() }
        } else {
            AppLog.i(TAG, "Battery recovered: resuming IMAP IDLE push")
        }
    }

    /**
     * Clean shutdown for the dataSync FGS runtime-cap timeout (issue #302): re-assert the periodic
     * sync fallback, swap the persistent notification to the degraded text and DETACH it so it stays
     * posted after we leave foreground state, then stop the service. Stopping foreground state is not
     * optional here — a `dataSync` service that is still foreground when its timeout elapses is the
     * exact condition the platform force-stops (and throws) on, so we must not keep running as an FGS.
     * [stopSelf] then tears down [scope] in [onDestroy], closing the IDLE connections; mail arrives via
     * the 15-minute periodic sync until push is started again (next app foreground / cap reset).
     */
    // Permission is checked via hasNotificationPermission() below; lint can't trace the indirect guard.
    @SuppressLint("MissingPermission")
    private fun fallBackToPeriodicSync() {
        AppLog.i(TAG, "dataSync FGS runtime cap reached: pausing IMAP IDLE; mail arrives via 15-minute periodic sync")
        // Already scheduled at every app start (UPDATE, so a no-op here) — re-asserted so the fallback
        // provably exists now that push is paused, mirroring the low-battery path in onPushModeChanged.
        syncScheduler.schedulePeriodicSync()
        // Re-post FOREGROUND_ID with the degraded text and DETACH it (below) so it survives leaving
        // foreground state. Skipped without POST_NOTIFICATIONS (API 33+ denied), where no status
        // notification is shown anyway; the detach then simply leaves nothing posted.
        if (hasNotificationPermission()) {
            PushStatusNotification.ensureChannel(this)
            NotificationManagerCompat.from(this).notify(
                PushStatusNotification.FOREGROUND_ID,
                PushStatusNotification.build(this, PushMode.POLLING, timedOut = true),
            )
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun hasNotificationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Holds IDLE for one account, reconnecting with exponential backoff whenever it drops.
     * Each IDLE session is bounded by [IDLE_RENEWAL_MS]: when it elapses, [withTimeoutOrNull]
     * cancels idle() (which closes the connection to unblock it) and we reconnect with a fresh
     * IDLE. This re-issues IDLE well within RFC 2177's 29-minute limit and before NAT/firewall
     * idle-socket timeouts would silently strand the connection. Each reconnect catches up via
     * idle()'s on-connect sync, so no mail is missed across renewals.
     */
    private suspend fun watchAccount(account: Account) {
        AppLog.i(TAG, "IDLE watch start ${accountLogRef(account.id)}")
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
                AppLog.w(TAG, "IDLE for ${accountLogRef(account.id)} dropped; retrying in ${backoffMs}ms", e)
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

    /** Starts (or, on later calls, updates) the foreground notification for the current push [mode]. */
    private fun startAsForeground(mode: PushMode) {
        PushStatusNotification.ensureChannel(this)
        ServiceCompat.startForeground(
            this,
            PushStatusNotification.FOREGROUND_ID,
            PushStatusNotification.build(this, mode),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private companion object {
        const val TAG = "IdleService"
        const val INITIAL_BACKOFF_MS = 5_000L
        const val MAX_BACKOFF_MS = 5 * 60_000L

        // Cadence of the reuse-cache idle-eviction sweep (issue #357 Part 2). Tighter than the reuse
        // idle timeout so an idle socket is closed shortly after it crosses it.
        const val REUSE_EVICTION_SWEEP_MS = 2 * 60_000L

        // Re-establish IDLE on this cadence — under RFC 2177's 29-minute ceiling and short enough
        // to beat typical NAT/firewall idle-socket timeouts.
        const val IDLE_RENEWAL_MS = 9 * 60_000L
    }
}
