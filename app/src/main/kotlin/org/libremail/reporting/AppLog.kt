// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.reporting

import android.util.Log

/**
 * Logging facade that mirrors Logcat output into the process [RingLogBuffer] so recent activity can
 * be attached to a user-reviewed [DebugReport]. Call [install] once at startup. Never pass PII
 * (email addresses, message content, credentials) to these methods — the buffer can end up in a
 * report the user reviews and may submit.
 *
 * The throwable-carrying [d]/[w]/[e] overloads forward the throwable to Logcat as usual, but record a
 * **PII-scrubbed** rendering of its stack trace into the buffer via [StackTraceScrubber]: exception
 * class names and stack frames are kept, while exception *messages* — which can embed a server host or
 * an account email (e.g. a `ConnectException` or an auth failure) — are stripped. To refer to an
 * account in a log line, log [accountLogRef]`(account.id)` rather than the raw id, which is PII.
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

    fun d(tag: String, message: String, throwable: Throwable?) {
        Log.d(tag, message, throwable)
        buffer?.record('D', tag, bufferLine(message, throwable))
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        buffer?.record('I', tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
        buffer?.record('W', tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable?) {
        Log.w(tag, message, throwable)
        buffer?.record('W', tag, bufferLine(message, throwable))
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        buffer?.record('E', tag, bufferLine(message, throwable))
    }

    /**
     * The buffer text for a log call: the caller [message] alone, or — when a [throwable] is present —
     * the message followed by the throwable's **scrubbed** stack trace (exception class names and stack
     * frames kept; host/email-bearing exception messages stripped by [StackTraceScrubber]).
     */
    private fun bufferLine(message: String, throwable: Throwable?): String {
        if (throwable == null) return message
        return "$message\n" + StackTraceScrubber.scrub(throwable.stackTraceToString())
    }
}
