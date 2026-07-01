// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.reporting

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.libremail.data.settings.AppSettings
import org.libremail.data.settings.FetchPolicy
import org.libremail.data.settings.SettingsRepository
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagnosticsCollectorTest {

    private val appVersion = mockk<AppVersionProvider> {
        every { versionName } returns "1.2.3"
        every { versionCode } returns 42L
    }
    private val settingsRepository = mockk<SettingsRepository>()
    private val logBuffer = RingLogBuffer()
    private val collector = DiagnosticsCollector(appVersion, settingsRepository, logBuffer)

    @Test
    fun `crash report includes stack trace and app version`() = runTest {
        val report = collector.collectCrash(RuntimeException("kaboom"))

        assertEquals(ReportKind.CRASH, report.kind)
        assertEquals("1.2.3", report.appVersionName)
        assertEquals(42L, report.appVersionCode)
        assertTrue(report.stackTrace.orEmpty().contains("kaboom"))
    }

    @Test
    fun `manual report includes a minimal non-PII settings summary and no stack trace`() = runTest {
        every { settingsRepository.settings } returns
            flowOf(AppSettings(pushIdle = false, fetchPolicy = FetchPolicy.ON_DEMAND))

        val report = collector.collectManual()

        assertEquals(ReportKind.MANUAL, report.kind)
        assertNull(report.stackTrace)
        assertEquals("false", report.settings["pushIdle"])
        assertEquals("ON_DEMAND", report.settings["fetchPolicy"])
        // The summary is a fixed allow-list of non-PII flags — no account/server fields.
        assertEquals(
            setOf(
                "dynamicColor",
                "newMailNotifications",
                "pushIdle",
                "allowStartTls",
                "loadRemoteImages",
                "encryptCache",
                "fetchPolicy",
            ),
            report.settings.keys,
        )
        assertTrue(report.settings.values.none { it.contains("@") })
    }

    @Test
    fun `crash report includes settings once the cache is warmed`() = runTest {
        every { settingsRepository.settings } returns flowOf(AppSettings(encryptCache = true))
        collector.warmSettingsCache()

        val report = collector.collectCrash(RuntimeException("x"))

        assertEquals("true", report.settings["encryptCache"])
    }

    @Test
    fun `crash report captures recent in-app log lines`() = runTest {
        logBuffer.record('I', "Startup", "hello-breadcrumb")

        val report = collector.collectCrash(RuntimeException("x"))

        assertTrue(report.logs.any { it.contains("hello-breadcrumb") })
    }
}
