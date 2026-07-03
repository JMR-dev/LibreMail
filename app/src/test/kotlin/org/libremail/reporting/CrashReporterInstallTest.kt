// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.reporting

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.libremail.data.settings.SettingsRepository
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers [CrashReporter.install]: the installed handler must persist the crash locally AND still chain
 * to the previous handler so the OS crash dialog/termination is preserved. (The no-previous branch
 * calls `Process.killProcess` + `exitProcess`, which cannot be exercised in a JVM unit test without
 * tearing down the test process, so it stays for the instrumented suite.) Added as a separate file so
 * it doesn't collide with the in-flight [CrashReporterTest] changes.
 */
class CrashReporterInstallTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val appVersion = mockk<AppVersionProvider> {
        every { versionName } returns "0.1.0"
        every { versionCode } returns 1L
    }
    private val settingsRepository = mockk<SettingsRepository>()

    private var original: Thread.UncaughtExceptionHandler? = null

    @Before
    fun saveHandler() {
        original = Thread.getDefaultUncaughtExceptionHandler()
    }

    @After
    fun restoreHandler() {
        Thread.setDefaultUncaughtExceptionHandler(original)
    }

    @Test
    fun `install persists the crash and still chains to the previous handler`() {
        val previous = mockk<Thread.UncaughtExceptionHandler>(relaxed = true)
        Thread.setDefaultUncaughtExceptionHandler(previous)
        val store = ReportStore(tempFolder.root)
        val buffer = RingLogBuffer()
        val collector = DiagnosticsCollector(appVersion, settingsRepository, buffer)
        val reporter = CrashReporter(collector, store, buffer)

        reporter.install()
        val installed = requireNotNull(Thread.getDefaultUncaughtExceptionHandler())
        val thread = Thread.currentThread()
        val crash = IllegalStateException("uncaught boom")
        installed.uncaughtException(thread, crash)

        // Persisted for next-launch review...
        assertEquals(1, store.reports.value.size)
        assertEquals(ReportKind.CRASH, store.reports.value.single().kind)
        // ...and the OS's original handler still ran, so the system crash still surfaces.
        verify { previous.uncaughtException(thread, crash) }
    }

    @Test
    fun `only a genuine uncaught exception creates a crash report - update, force-stop, swipe-away do not`() {
        Thread.setDefaultUncaughtExceptionHandler(mockk(relaxed = true))
        val store = ReportStore(tempFolder.root)
        val buffer = RingLogBuffer()
        val collector = DiagnosticsCollector(appVersion, settingsRepository, buffer)
        val reporter = CrashReporter(collector, store, buffer)
        reporter.install()
        val installed = requireNotNull(Thread.getDefaultUncaughtExceptionHandler())

        // An app update (killDueToPackageUpdate), a force-stop, and a user swipe-away/task-removal all
        // end the process WITHOUT delivering an uncaught throwable to this handler, so none of them
        // creates a report and the startup prompt stays silent (#255 criterion 3). Reports come solely
        // from a genuine uncaught crash routing through the installed handler.
        assertTrue(store.reports.value.isEmpty())

        installed.uncaughtException(Thread.currentThread(), IllegalStateException("real crash"))

        assertEquals(1, store.reports.value.size)
        assertEquals(ReportKind.CRASH, store.reports.value.single().kind)
    }
}
