// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.reporting

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * File-backed store of pending [DebugReport]s — one JSON file per report under [directory].
 * Deliberately NOT Room-backed: a crash-time save must be simple and robust, independent of the
 * (possibly encrypted, possibly mid-migration) app database. Reports persist until the user submits
 * or discards them. [reports] is a snapshot that updates on every [save]/[delete].
 *
 * The store is an eager `@Singleton` dependency of `CrashReporter`, whose `install()` runs on the
 * MAIN thread in `Application.onCreate()`. Listing + reading + JSON-parsing every stored report there
 * would be main-thread disk I/O (#296), so the flow is seeded empty and the initial scan is dispatched
 * to [scope] (IO by default). Reactive consumers (Problem Reports, the startup prompt) observe
 * [reports] and update when the scan lands; the empty window is momentary. Writes ([save] etc.)
 * re-scan synchronously so a crash-time save is never lost to the pending initial scan.
 */
class ReportStore(
    private val directory: File,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val lock = Any()
    private val _reports = MutableStateFlow<List<DebugReport>>(emptyList())
    val reports: StateFlow<List<DebugReport>> = _reports.asStateFlow()

    init {
        scope.launch { refresh() }
    }

    private fun refresh() {
        synchronized(lock) { _reports.value = scan() }
    }

    fun save(report: DebugReport) {
        synchronized(lock) {
            directory.mkdirs()
            File(directory, fileName(report.id)).writeText(report.toStorageJson())
            _reports.value = scan()
        }
    }

    fun find(id: String): DebugReport? = _reports.value.firstOrNull { it.id == id }

    /**
     * Persistently marks a report as auto-surfaced so the startup crash prompt offers it at most once
     * across launches (see #255). The report itself stays in the store (still listed under Problem
     * Reports); only [delete] removes it. No-op if the report is missing or already surfaced.
     */
    fun markSurfaced(id: String) {
        synchronized(lock) {
            val report = _reports.value.firstOrNull { it.id == id } ?: return
            if (report.surfaced) return
            File(directory, fileName(id)).writeText(report.copy(surfaced = true).toStorageJson())
            _reports.value = scan()
        }
    }

    fun delete(id: String) {
        synchronized(lock) {
            File(directory, fileName(id)).delete()
            _reports.value = scan()
        }
    }

    /**
     * Deletes stored reports created before [cutoffMillis], returning how many were purged. Drives the
     * charging-time housekeeping job (issue #239) so old crash/problem records don't pile up on-device.
     */
    fun purgeOlderThan(cutoffMillis: Long): Int = synchronized(lock) {
        val stale = _reports.value.filter { it.createdAtMillis < cutoffMillis }
        stale.forEach { File(directory, fileName(it.id)).delete() }
        if (stale.isNotEmpty()) _reports.value = scan()
        stale.size
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
