// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.reporting

import io.mockk.every
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.libremail.data.settings.SettingsRepository
import org.libremail.domain.repository.AccountRepository
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrashReporterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val appVersion = mockk<AppVersionProvider> {
        every { versionName } returns "0.1.0"
        every { versionCode } returns 1L
    }
    private val settingsRepository = mockk<SettingsRepository>()
    private val accountRepository = mockk<AccountRepository>()

    @Test
    fun `persisting a forced crash saves a report offered on next launch`() {
        val store = ReportStore(tempFolder.root)
        val buffer = RingLogBuffer()
        val collector = DiagnosticsCollector(appVersion, settingsRepository, accountRepository, buffer)
        val reporter = CrashReporter(collector, store, buffer)

        reporter.persist(IllegalStateException("forced crash"))

        // Saved locally, with the stack trace and a crash breadcrumb captured.
        val saved = store.reports.value.single()
        assertEquals(ReportKind.CRASH, saved.kind)
        assertTrue(saved.stackTrace.orEmpty().contains("forced crash"))
        assertTrue(saved.logs.any { it.contains("Uncaught exception") })

        // Still available to a fresh store instance, simulating the next app launch.
        val nextLaunch = ReportStore(tempFolder.root)
        assertEquals(1, nextLaunch.reports.value.size)
        assertEquals(ReportKind.CRASH, nextLaunch.reports.value.single().kind)
    }

    @Test
    fun `capture only persists — it has no path to transmit`() {
        // CrashReporter is constructed without any submitter/scheduler, so a crash can only ever be
        // written to the local store. Nothing here can send data off the device.
        val store = ReportStore(tempFolder.root)
        val buffer = RingLogBuffer()
        val collector = DiagnosticsCollector(appVersion, settingsRepository, accountRepository, buffer)
        val reporter = CrashReporter(collector, store, buffer)

        reporter.persist(RuntimeException("boom"))

        assertEquals(1, store.reports.value.size)
    }
}
