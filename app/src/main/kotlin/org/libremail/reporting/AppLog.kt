// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.reporting

import android.util.Log

/**
 * Logging facade that mirrors Logcat output into the process [RingLogBuffer] so recent activity can
 * be attached to a user-reviewed [DebugReport]. Call [install] once at startup. Never pass PII
 * (email addresses, message content, credentials) to these methods — the buffer can end up in a
 * report the user reviews and may submit.
 */
object AppLog {
    @Volatile
    private var buffer: RingLogBuffer? = null

    fun install(buffer: RingLogBuffer) {
        this.buffer = buffer
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        buffer?.record('D', tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        buffer?.record('I', tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
        buffer?.record('W', tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        buffer?.record('E', tag, message)
    }
}
