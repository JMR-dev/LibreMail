// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.reporting

import android.os.Process
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.exitProcess

/**
 * Installs a process-wide uncaught-exception handler that persists a crash [DebugReport] locally for
 * review on next launch. Reports are NEVER sent automatically — submission is strictly user-initiated
 * (see [ReportSubmitter]). The previous handler is always invoked so the system still shows the crash
 * and terminates the process.
 */
@Singleton
class CrashReporter @Inject constructor(
    private val collector: DiagnosticsCollector,
    private val store: ReportStore,
    private val logBuffer: RingLogBuffer,
) {
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            persist(throwable)
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                terminate()
            }
        }
    }

    /**
     * Saves a crash report. Wrapped so a failure here can never mask the original crash. Exposed for
     * tests; installing the handler simply routes uncaught exceptions here.
     */
    fun persist(throwable: Throwable) {
        runCatching {
            logBuffer.record('E', TAG, "Uncaught exception: ${throwable.javaClass.name}")
            store.save(collector.collectCrash(throwable))
        }
    }

    private fun terminate() {
        Process.killProcess(Process.myPid())
        exitProcess(EXIT_FAILURE)
    }

    private companion object {
        const val TAG = "CrashReporter"
        const val EXIT_FAILURE = 10
    }
}
