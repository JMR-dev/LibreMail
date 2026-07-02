// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.libremail.data.settings.SettingsRepository
import org.libremail.data.sync.SyncScheduler
import org.libremail.domain.repository.AccountRepository
import org.libremail.push.IdlePushManager
import org.libremail.reporting.AppLog
import org.libremail.reporting.CrashReporter
import org.libremail.reporting.DiagnosticsCollector
import org.libremail.reporting.RingLogBuffer
import javax.inject.Inject

@HiltAndroidApp
class LibreMailApplication :
    Application(),
    Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var syncScheduler: SyncScheduler

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var accountRepository: AccountRepository

    @Inject lateinit var idlePushManager: IdlePushManager

    @Inject lateinit var ringLogBuffer: RingLogBuffer

    @Inject lateinit var crashReporter: CrashReporter

    @Inject lateinit var diagnosticsCollector: DiagnosticsCollector

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Whether the IDLE push service should currently be running (push enabled AND an account exists). */
    @Volatile
    private var pushShouldBeActive = false

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Wire up debug reporting first so crashes during the rest of startup are still captured.
        AppLog.install(ringLogBuffer)
        crashReporter.install()
        AppLog.i(TAG, "Application created")
        // Warm the settings cache so a later crash report can include non-PII settings without
        // touching DataStore on the crashing thread.
        appScope.launch { runCatching { diagnosticsCollector.warmSettingsCache() } }
        syncScheduler.schedulePeriodicSync()
        // Full-history backfill (#12) and device-only retention pruning (#13) run as their own bounded,
        // resumable background jobs so they never block foreground sync / pull-to-refresh.
        syncScheduler.schedulePeriodicBackfill()
        syncScheduler.schedulePeriodicPrune()
        // Run the IMAP IDLE push service only while it has something to do: the push setting is on
        // AND at least one account exists. This starts it when the first account is added and stops
        // it when the last is removed, reactively.
        appScope.launch {
            combine(
                settingsRepository.settings.map { it.pushIdle },
                accountRepository.observeAccounts().map { it.isNotEmpty() },
            ) { pushEnabled, hasAccounts -> pushEnabled && hasAccounts }
                .distinctUntilChanged()
                .collect { active ->
                    pushShouldBeActive = active
                    if (active) idlePushManager.start() else idlePushManager.stop()
                }
        }
    }

    /**
     * Re-attempts starting the IDLE push service. A start from the background can be blocked
     * (ForegroundServiceStartNotAllowedException) and is swallowed; because the active/inactive
     * state hasn't changed, the collector above won't retry, so the foreground (MainActivity) calls
     * this to recover. Safe to call repeatedly — starting an already-running service is a no-op.
     */
    fun ensurePushStarted() {
        if (pushShouldBeActive) idlePushManager.start()
    }

    private companion object {
        const val TAG = "LibreMail"
    }
}
