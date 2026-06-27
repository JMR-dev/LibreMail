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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.libremail.data.settings.SettingsRepository
import org.libremail.data.sync.SyncScheduler
import org.libremail.domain.repository.AccountRepository
import org.libremail.push.IdlePushManager

@HiltAndroidApp
class LibreMailApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var syncScheduler: SyncScheduler

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var accountRepository: AccountRepository

    @Inject lateinit var idlePushManager: IdlePushManager

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
                .collect { active -> if (active) idlePushManager.start() else idlePushManager.stop() }
        }
    }
}
