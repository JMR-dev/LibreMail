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
        syncScheduler.schedulePeriodicSync()
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
}
