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
 *
 * At-rest encryption (issue #369): when [encryption] reports it is [ReportEncryption.enabled], each
 * report's storage JSON is sealed before it is written and tagged with [ENCRYPTED_PREFIX]; when it is
 * off the JSON is stored plaintext exactly as before. Reads sniff the prefix, so plaintext reports from
 * before the setting was turned on and sealed reports written after it coexist transparently. Writes
 * FAIL CLOSED: if sealing throws while encryption is on, the report is dropped rather than written in
 * plaintext, so the user's opt-in encryption is never silently defeated by leaving a plaintext report
 * on disk. The default [ReportEncryption.None] keeps the store plaintext (its historical behaviour).
 */
class ReportStore(
    private val directory: File,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val encryption: ReportEncryption = ReportEncryption.None,
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
            val serialized = serializeForDisk(report) ?: return
            directory.mkdirs()
            File(directory, fileName(report.id)).writeText(serialized)
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
            val serialized = serializeForDisk(report.copy(surfaced = true)) ?: return
            File(directory, fileName(id)).writeText(serialized)
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
            .mapNotNull { file -> readReport(file) }
            .sortedByDescending { it.createdAtMillis }
    }

    /**
     * Renders [report] for on-disk storage. With encryption OFF this is the plaintext storage JSON,
     * byte-for-byte as before. With it ON the JSON is sealed and tagged with [ENCRYPTED_PREFIX]. Returns
     * `null` — so the caller writes nothing — when sealing fails while encryption is ON: the report is
     * deliberately NOT written in plaintext (that would defeat the user's opt-in encryption, #369), so a
     * failed seal drops the report rather than leaking it. A dropped crash report still lets the original
     * crash propagate to the system handler.
     */
    private fun serializeForDisk(report: DebugReport): String? {
        val json = report.toStorageJson()
        if (!encryption.enabled()) return json
        return runCatching { ENCRYPTED_PREFIX + encryption.encrypt(json) }.getOrElse { e ->
            AppLog.e(TAG, "Report encryption failed; not persisting to avoid a plaintext report on disk", e)
            null
        }
    }

    /**
     * Reads one stored report, transparently unsealing files tagged with [ENCRYPTED_PREFIX]. A file that
     * cannot be decrypted (e.g. the master key was cleared) is logged and skipped rather than crashing
     * the list; an unparseable plaintext file is skipped silently, as before.
     */
    private fun readReport(file: File): DebugReport? {
        val raw = runCatching { file.readText() }.getOrNull() ?: return null
        val json = if (raw.startsWith(ENCRYPTED_PREFIX)) {
            runCatching { encryption.decrypt(raw.removePrefix(ENCRYPTED_PREFIX)) }.getOrElse { e ->
                AppLog.w(TAG, "Skipping a stored report that could not be decrypted", e)
                return null
            }
        } else {
            raw
        }
        return runCatching { DebugReport.fromStorageJson(json) }.getOrNull()
    }

    private fun fileName(id: String) = "$id$SUFFIX"

    private companion object {
        const val SUFFIX = ".json"
        const val TAG = "ReportStore"

        /**
         * Marks a file whose body is `Base64(iv || ciphertext)` rather than plaintext JSON. Contains
         * characters outside Base64's alphabet (`.`/`:`) and never matches a plaintext report's leading
         * `{`, so a read tells sealed from plaintext files unambiguously — the two coexist on disk after
         * the encryption setting is toggled.
         */
        const val ENCRYPTED_PREFIX = "libremail.report.enc.v1:"
    }
}
