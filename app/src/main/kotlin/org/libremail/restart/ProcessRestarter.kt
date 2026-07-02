// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.restart

import android.content.Context
import android.content.Intent
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Relaunches the whole app in a brand-new process by handing off to [RestartActivity], a trampoline
 * that runs in the separate `:restart` process. Because the trampoline survives the current process
 * being killed, the relaunch it issues cannot be dropped by ActivityManager scheduling it into the
 * dying process — the failure mode of a same-process "startActivity then exit(0)" restart.
 *
 * Used by the app-lock key-invalidation recovery to bounce the process so the cache is wiped safely at
 * the next cold start (before Room reopens it).
 */
@Singleton
class ProcessRestarter @Inject constructor(@ApplicationContext private val context: Context) {

    /**
     * Start the [RestartActivity] trampoline in the `:restart` process, passing it this (main) process
     * PID so it can kill us and relaunch from the outside. Returns immediately; the actual kill +
     * relaunch happens in the trampoline process moments later.
     */
    fun restart() {
        val trampoline = Intent(context, RestartActivity::class.java).apply {
            // Required because we may be started from a non-Activity (Application) context.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(RestartActivity.EXTRA_ORIGINAL_PID, Process.myPid())
        }
        context.startActivity(trampoline)
    }

    companion object {
        /**
         * The `android:process` suffix of [RestartActivity] (must match AndroidManifest.xml). The
         * Application uses it to skip its normal startup work when it is spun up in this aux process.
         */
        const val PROCESS_SUFFIX = ":restart"
    }
}
