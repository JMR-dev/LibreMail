// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.reporting

import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** One captured log line. */
data class LogEntry(val timeMillis: Long, val level: Char, val tag: String, val message: String) {
    fun formatted(): String = "${Instant.ofEpochMilli(timeMillis)} $level/$tag: $message"
}

/**
 * A small, thread-safe, in-memory ring buffer of recent app log lines. It is held in memory only
 * (never written to disk on its own) and copied into a [DebugReport] on request. Callers must never
 * record PII (email addresses, message content, credentials) — see [AppLog].
 */
@Singleton
class RingLogBuffer @Inject constructor() {
    private val lock = Any()
    private val entries = ArrayDeque<LogEntry>()

    fun record(level: Char, tag: String, message: String) {
        synchronized(lock) {
            if (entries.size >= CAPACITY) entries.removeFirst()
            entries.addLast(LogEntry(System.currentTimeMillis(), level, tag, message))
        }
    }

    fun snapshot(): List<LogEntry> = synchronized(lock) { entries.toList() }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    private companion object {
        const val CAPACITY = 200
    }
}
