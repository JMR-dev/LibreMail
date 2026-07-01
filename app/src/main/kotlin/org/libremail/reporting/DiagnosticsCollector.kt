// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.reporting

import android.os.Build
import kotlinx.coroutines.flow.first
import org.libremail.data.settings.AppSettings
import org.libremail.data.settings.SettingsRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Assembles a [DebugReport] from app/device metadata, a stack trace (for crashes), a minimal
 * non-PII settings summary, and the recent in-app log buffer. Only the fields listed in [summarize]
 * are captured — deliberately no account emails, server names, or message content.
 */
@Singleton
class DiagnosticsCollector @Inject constructor(
    private val appVersion: AppVersionProvider,
    private val settingsRepository: SettingsRepository,
    private val logBuffer: RingLogBuffer,
) {
    // Cached so a crash report (built synchronously on the crashing thread) can still include
    // settings without touching DataStore. Refreshed whenever settings are read for a manual
    // report or explicitly warmed at startup.
    @Volatile
    private var cachedSettings: Map<String, String> = emptyMap()

    /** Pre-reads settings so a later crash report can include them. Safe to call and ignore. */
    suspend fun warmSettingsCache() {
        cachedSettings = summarize(settingsRepository.settings.first())
    }

    /** Builds a report for a user-initiated ("Report a problem") request; includes live settings. */
    suspend fun collectManual(): DebugReport {
        val settings = summarize(settingsRepository.settings.first())
        cachedSettings = settings
        return build(ReportKind.MANUAL, throwable = null, settings = settings)
    }

    /** Builds a crash report synchronously; it must not block or throw on the crashing thread. */
    fun collectCrash(throwable: Throwable): DebugReport =
        build(ReportKind.CRASH, throwable = throwable, settings = cachedSettings)

    private fun build(kind: ReportKind, throwable: Throwable?, settings: Map<String, String>) = DebugReport(
        id = UUID.randomUUID().toString(),
        createdAtMillis = System.currentTimeMillis(),
        kind = kind,
        appVersionName = appVersion.versionName,
        appVersionCode = appVersion.versionCode,
        androidRelease = Build.VERSION.RELEASE ?: "",
        androidSdkInt = Build.VERSION.SDK_INT,
        deviceManufacturer = Build.MANUFACTURER ?: "",
        deviceModel = Build.MODEL ?: "",
        stackTrace = throwable?.stackTraceToString(),
        settings = settings,
        logs = logBuffer.snapshot().map { it.formatted() },
    )

    private fun summarize(settings: AppSettings): Map<String, String> = linkedMapOf(
        "dynamicColor" to settings.dynamicColor.toString(),
        "newMailNotifications" to settings.newMailNotifications.toString(),
        "pushIdle" to settings.pushIdle.toString(),
        "allowStartTls" to settings.allowStartTls.toString(),
        "loadRemoteImages" to settings.loadRemoteImages.toString(),
        "encryptCache" to settings.encryptCache.toString(),
        "fetchPolicy" to settings.fetchPolicy.name,
    )
}
