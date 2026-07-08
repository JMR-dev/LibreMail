// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.reporting

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Best-effort PII redaction applied to a [DebugReport] immediately before it is encrypted and uploaded
 * (issue #34). It is the LAST line of defence, not the first: a report is already PII-free by
 * construction — [DiagnosticsCollector] captures only coarse device/app/settings metadata and a
 * bucketed provider list (no emails, hosts, or message content), and [StackTraceScrubber] strips the
 * host/username-bearing message text out of every stack trace at capture time. This pass re-scrubs the
 * two surfaces that can still carry PII the earlier stages never saw:
 *  - [DebugReport.userComment] — free text the user typed, which may paste an address, a server name,
 *    an error string with a token, etc.;
 *  - [DebugReport.logs] — already governed by the `AppLog` "no PII" contract, re-scrubbed here as
 *    defence-in-depth in case a log line slipped a raw value through.
 *
 * [DebugReport.userEmail] is deliberately NOT touched: it is the reply-to address the user chose to
 * supply when submitting (issue #159), so it is consented, purposeful data rather than leaked PII —
 * see `docs/debug-report-privacy.md`. Everything else in the report is non-PII by construction, so it
 * is passed through unchanged.
 *
 * Redaction is intentionally conservative-but-lossy ("best-effort" per the ticket): it favours
 * removing a real secret over preserving a false positive, so a `file.kt:42`-shaped token in free text
 * may be redacted too. [hasResidualPii] lets the caller log (never block) when a PII shape survives.
 */
@Singleton
class ReportAnonymizer @Inject constructor() {

    /** Returns a copy of [report] with free-text and log surfaces PII-redacted; other fields unchanged. */
    fun anonymize(report: DebugReport): DebugReport = report.copy(
        userComment = redact(report.userComment),
        stackTrace = report.stackTrace?.let(StackTraceScrubber::scrub),
        logs = report.logs.map(::redact),
    )

    /**
     * True when a PII *shape* (email, host:port, IPv4, or a JWT-like token) still appears in the
     * redactable surfaces of [report]. Used only to log a best-effort warning — [DebugReport.userEmail]
     * is excluded because it is intentionally retained (see the class KDoc).
     */
    fun hasResidualPii(report: DebugReport): Boolean {
        val surfaces = report.logs + report.userComment + report.stackTrace.orEmpty()
        return surfaces.any { line -> RESIDUAL_SHAPES.any { it.containsMatchIn(line) } }
    }

    private fun redact(text: String): String {
        if (text.isEmpty()) return text
        var out = text
        out = SECRET_ASSIGNMENT.replace(out) { "${it.groupValues[1]}=$REDACTED" }
        out = BEARER.replace(out, "Bearer $REDACTED")
        out = BASIC.replace(out, "Basic $REDACTED")
        out = JWT.replace(out, REDACTED)
        out = EMAIL.replace(out, REDACTED)
        out = HOST_PORT.replace(out, REDACTED)
        out = IPV4.replace(out, REDACTED)
        return out
    }

    private companion object {
        const val REDACTED = "[redacted]"

        /** `user@host.tld`. Mirrors [StackTraceScrubber]'s address pattern. */
        val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")

        /** A dotted host or IPv4 followed by `:port`, incl. the `host/1.2.3.4:port` rendering. */
        val HOST_PORT = Regex("""[A-Za-z0-9.-]+(?:/[0-9.]+)?:\d{2,5}""")

        /** A bare dotted-quad IPv4 address. */
        val IPV4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")

        /** A three-segment `eyJ…`-prefixed JWT (access/id tokens the app might touch). */
        val JWT = Regex("""\beyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}""")

        /**
         * `key: value` / `key=value` where the key names a credential; keeps the key, drops the value.
         * The `Authorization` header is deliberately NOT a key here — its value carries a scheme + a
         * space (`Bearer …` / `Basic …`), so [BEARER] / [BASIC] redact the whole token instead.
         */
        val SECRET_ASSIGNMENT = Regex(
            """(?i)\b(password|passwd|pwd|secret|client[_-]?secret|access[_-]?token|""" +
                """refresh[_-]?token|token|api[_-]?key)\b\s*[:=]\s*"?[^\s"]+""",
        )

        /** `Bearer <token>` authorization values. */
        val BEARER = Regex("""(?i)\bbearer\s+[A-Za-z0-9._~+/-]+=*""")

        /** `Basic <base64(user:pass)>` authorization values. */
        val BASIC = Regex("""(?i)\bbasic\s+[A-Za-z0-9+/=]+""")

        /** The residual PII *shapes* [hasResidualPii] flags after redaction (structural forms excluded). */
        val RESIDUAL_SHAPES = listOf(EMAIL, HOST_PORT, IPV4, JWT)
    }
}
