// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.libremail.data.settings.SettingsRepository
import org.libremail.data.sync.SyncScheduler
import org.libremail.push.IdlePushManager

@HiltAndroidApp
class LibreMailApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var syncScheduler: SyncScheduler

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var idlePushManager: IdlePushManager

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        syncScheduler.schedulePeriodicSync()
        // Start or stop the IMAP IDLE push service to match the user's preference, reactively.
        appScope.launch {
            settingsRepository.settings
                .map { it.pushIdle }
                .distinctUntilChanged()
                .collect { enabled -> if (enabled) idlePushManager.start() else idlePushManager.stop() }
        }
    }
}
