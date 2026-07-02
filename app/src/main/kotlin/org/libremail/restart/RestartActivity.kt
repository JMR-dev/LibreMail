// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.restart

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.util.Log

/**
 * Separate-process trampoline that performs an app relaunch from OUTSIDE the process being killed.
 *
 * Declared in the manifest with `android:process=":restart"`, so Android runs it in its own process.
 * That is the whole point: it kills the original (main) process by PID and only THEN starts the main
 * launcher activity, so the relaunch is scheduled from a process that is NOT the one being torn down.
 * A same-process "startActivity then Runtime.exit(0)" restart races ActivityManager — the relaunch can
 * be scheduled into the dying process and silently dropped, so the app just closes. Issuing it from a
 * surviving process (the ProcessPhoenix pattern) makes the relaunch reliable.
 *
 * DEVICE-ONLY: the multi-process kill/relaunch cannot be exercised in JVM unit tests; see
 * [ProcessRestarter] for the (testable) intent/targeting seam and AppLockViewModelTest for the
 * ordering guarantees around it.
 */
class RestartActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Kill the original main process FIRST so the relaunch below spins up a genuinely fresh
        // process — one whose cold DatabaseModule performs the pending cache wipe before Room opens.
        // If we relaunched while the old process were still alive, ActivityManager could route the
        // launch back into it and the wipe-on-cold-start would never run.
        val originalPid = intent.getIntExtra(EXTRA_ORIGINAL_PID, INVALID_PID)
        if (originalPid > INVALID_PID && originalPid != Process.myPid()) {
            Process.killProcess(originalPid)
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            Log.w(TAG, "no launch intent for $packageName; cannot relaunch after restart")
        }

        finish()
        // Tear down this trampoline process too: its only job was to issue the relaunch from outside
        // the dying main process.
        Runtime.getRuntime().exit(0)
    }

    companion object {
        /** Extra carrying the PID of the main process to kill, so the relaunch starts a fresh one. */
        const val EXTRA_ORIGINAL_PID = "org.libremail.restart.ORIGINAL_PID"

        private const val INVALID_PID = -1
        private const val TAG = "LibreMailRestart"
    }
}
