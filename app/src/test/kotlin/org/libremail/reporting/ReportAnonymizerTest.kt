// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.reporting

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [ReportAnonymizer] is the pre-upload best-effort PII scrub. These feed representative PII through the
 * two surfaces that can still carry it — the user's free-text comment and the log lines — and assert it
 * is redacted, while the intentionally-retained reply-to [DebugReport.userEmail] and the non-PII
 * metadata pass through untouched.
 */
class ReportAnonymizerTest {

    private val anonymizer = ReportAnonymizer()

    private fun report(
        userComment: String = "",
        userEmail: String = "",
        logs: List<String> = emptyList(),
        stackTrace: String? = null,
    ) = DebugReport(
        id = "rid",
        createdAtMillis = 1L,
        kind = ReportKind.MANUAL,
        appVersionName = "0.1.0",
        appVersionCode = 1,
        androidRelease = "14",
        androidSdkInt = 34,
        deviceManufacturer = "Google",
        deviceModel = "Pixel",
        stackTrace = stackTrace,
        settings = mapOf("dynamicColor" to "true"),
        logs = logs,
        accounts = listOf("Gmail (PASSWORD_IMAP)"),
        userComment = userComment,
        userEmail = userEmail,
    )

    @Test
    fun `redacts an email address in the user comment`() {
        val out = anonymizer.anonymize(report(userComment = "reply to me at alice@example.com please"))
        assertEquals("reply to me at [redacted] please", out.userComment)
    }

    @Test
    fun `redacts a host and port in the user comment`() {
        val out = anonymizer.anonymize(report(userComment = "fails talking to imap.example.com:993 always"))
        assertEquals("fails talking to [redacted] always", out.userComment)
    }

    @Test
    fun `redacts a bare IPv4 address in the user comment`() {
        val out = anonymizer.anonymize(report(userComment = "server at 93.184.216.34 is down"))
        assertEquals("server at [redacted] is down", out.userComment)
    }

    @Test
    fun `redacts a JWT-shaped token in the user comment`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N"
        val out = anonymizer.anonymize(report(userComment = "token was $jwt when it broke"))
        assertEquals("token was [redacted] when it broke", out.userComment)
    }

    @Test
    fun `redacts a bearer authorization value in a log line`() {
        val out = anonymizer.anonymize(report(logs = listOf("I/Auth sent header Authorization: Bearer abc123.DEF-456")))
        assertTrue(out.logs.single().endsWith("Bearer [redacted]"), out.logs.single())
    }

    @Test
    fun `keeps a credential key but redacts its value`() {
        val out = anonymizer.anonymize(report(userComment = "config had password=hunter2 in it"))
        assertEquals("config had password=[redacted] in it", out.userComment)
    }

    @Test
    fun `re-scrubs the stack trace through StackTraceScrubber`() {
        val trace = "java.net.ConnectException: Failed to connect to imap.example.com/93.184.216.34:993"
        val out = anonymizer.anonymize(report(stackTrace = trace))
        assertEquals("java.net.ConnectException", out.stackTrace)
    }

    @Test
    fun `retains the user-supplied reply-to email untouched`() {
        // userEmail is consented reply-to data (issue #159), not leaked PII: it must survive the pass.
        val out = anonymizer.anonymize(report(userEmail = "bob@example.org"))
        assertEquals("bob@example.org", out.userEmail)
    }

    @Test
    fun `leaves non-PII metadata unchanged`() {
        val input = report(userComment = "just a plain note")
        val out = anonymizer.anonymize(input)
        assertEquals(input.userComment, out.userComment)
        assertEquals(input.deviceModel, out.deviceModel)
        assertEquals(input.settings, out.settings)
        assertEquals(input.accounts, out.accounts)
    }

    @Test
    fun `an empty comment stays empty`() {
        assertEquals("", anonymizer.anonymize(report(userComment = "")).userComment)
    }

    @Test
    fun `hasResidualPii is false after anonymizing PII-laden text`() {
        val dirty = report(
            userComment = "alice@example.com via imap.example.com:993",
            logs = listOf("saw 10.0.0.5 on the wire"),
        )
        assertTrue(anonymizer.hasResidualPii(dirty))
        assertFalse(anonymizer.hasResidualPii(anonymizer.anonymize(dirty)))
    }

    @Test
    fun `hasResidualPii ignores the intentional reply-to email`() {
        // A clean report whose only address is the consented reply-to must NOT be flagged as residual PII.
        assertFalse(anonymizer.hasResidualPii(report(userEmail = "bob@example.org")))
    }
}
