// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.reporting

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Whether a report was produced by an uncaught crash or requested manually by the user. */
enum class ReportKind { CRASH, MANUAL }

/**
 * A locally-stored diagnostic report. It never leaves the device unless the user explicitly submits
 * it (see [ReportSubmitter]); [toSubmissionPayload] is the single rendering that is shown for
 * review, copied, saved to a file, and POSTed on submit, so what the user reads is exactly what is
 * sent. Only the fields assembled by `DiagnosticsCollector` are captured — no message content.
 */
@Suppress("LongParameterList") // A flat diagnostic DTO; grouping fields would only obscure the payload.
data class DebugReport(
    val id: String,
    val createdAtMillis: Long,
    val kind: ReportKind,
    val appVersionName: String,
    val appVersionCode: Long,
    val androidRelease: String,
    val androidSdkInt: Int,
    val deviceManufacturer: String,
    val deviceModel: String,
    val stackTrace: String?,
    val settings: Map<String, String>,
    val logs: List<String>,
    val userComment: String = "",
    /** Reply-to address the user supplied when submitting (see #159); required for online submit. */
    val userEmail: String = "",
) {
    /** The exact text shown for review, copied, saved to a file, and POSTed on submit. */
    fun toSubmissionPayload(): String = toJson().toString(JSON_INDENT)

    /** Compact form used for on-disk persistence. */
    fun toStorageJson(): String = toJson().toString()

    private fun toJson(): JSONObject {
        val app = JSONObject()
            .put("versionName", appVersionName)
            .put("versionCode", appVersionCode)
        val device = JSONObject()
            .put("manufacturer", deviceManufacturer)
            .put("model", deviceModel)
            .put("androidRelease", androidRelease)
            .put("sdkInt", androidSdkInt)
        val settingsJson = JSONObject()
        settings.forEach { (key, value) -> settingsJson.put(key, value) }
        val json = JSONObject()
            .put("id", id)
            .put("createdAt", Instant.ofEpochMilli(createdAtMillis).toString())
            .put("createdAtMillis", createdAtMillis)
            .put("kind", kind.name)
            .put("app", app)
            .put("device", device)
            .put("userComment", userComment)
            .put("userEmail", userEmail)
            .put("settings", settingsJson)
            .put("logs", JSONArray(logs))
        if (stackTrace != null) json.put("stackTrace", stackTrace)
        return json
    }

    companion object {
        private const val JSON_INDENT = 2

        fun fromStorageJson(raw: String): DebugReport {
            val json = JSONObject(raw)
            val app = json.getJSONObject("app")
            val device = json.getJSONObject("device")
            val settingsJson = json.getJSONObject("settings")
            val settings = LinkedHashMap<String, String>()
            settingsJson.keys().forEach { key -> settings[key] = settingsJson.getString(key) }
            val logsJson = json.getJSONArray("logs")
            val logs = ArrayList<String>(logsJson.length())
            for (i in 0 until logsJson.length()) logs.add(logsJson.getString(i))
            return DebugReport(
                id = json.getString("id"),
                createdAtMillis = json.getLong("createdAtMillis"),
                kind = ReportKind.valueOf(json.getString("kind")),
                appVersionName = app.getString("versionName"),
                appVersionCode = app.getLong("versionCode"),
                androidRelease = device.getString("androidRelease"),
                androidSdkInt = device.getInt("sdkInt"),
                deviceManufacturer = device.getString("manufacturer"),
                deviceModel = device.getString("model"),
                stackTrace = if (json.has("stackTrace")) json.getString("stackTrace") else null,
                settings = settings,
                logs = logs,
                userComment = json.optString("userComment", ""),
                userEmail = json.optString("userEmail", ""),
            )
        }
    }
}
