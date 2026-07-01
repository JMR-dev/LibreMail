// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.reporting

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * File-backed store of pending [DebugReport]s — one JSON file per report under [directory].
 * Deliberately NOT Room-backed: a crash-time save must be simple and robust, independent of the
 * (possibly encrypted, possibly mid-migration) app database. Reports persist until the user submits
 * or discards them. [reports] is a snapshot that updates on every [save]/[delete].
 */
class ReportStore(private val directory: File) {
    private val lock = Any()
    private val _reports = MutableStateFlow(scan())
    val reports: StateFlow<List<DebugReport>> = _reports.asStateFlow()

    fun save(report: DebugReport) {
        synchronized(lock) {
            directory.mkdirs()
            File(directory, fileName(report.id)).writeText(report.toStorageJson())
            _reports.value = scan()
        }
    }

    fun find(id: String): DebugReport? = _reports.value.firstOrNull { it.id == id }

    fun delete(id: String) {
        synchronized(lock) {
            File(directory, fileName(id)).delete()
            _reports.value = scan()
        }
    }

    private fun scan(): List<DebugReport> {
        val files = directory.listFiles { file -> file.isFile && file.name.endsWith(SUFFIX) }
            ?: return emptyList()
        return files
            .mapNotNull { file -> runCatching { DebugReport.fromStorageJson(file.readText()) }.getOrNull() }
            .sortedByDescending { it.createdAtMillis }
    }

    private fun fileName(id: String) = "$id$SUFFIX"

    private companion object {
        const val SUFFIX = ".json"
    }
}
