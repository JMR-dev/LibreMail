// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.reporting

import org.json.JSONObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DebugReportTest {

    private fun sample(
        kind: ReportKind = ReportKind.CRASH,
        stackTrace: String? = "java.lang.RuntimeException: boom\n\tat Foo.bar(Foo.kt:1)",
        comment: String = "",
    ) = DebugReport(
        id = "abc-123",
        createdAtMillis = 1_700_000_000_000L,
        kind = kind,
        appVersionName = "0.1.0",
        appVersionCode = 7,
        androidRelease = "14",
        androidSdkInt = 34,
        deviceManufacturer = "Google",
        deviceModel = "Pixel 8",
        stackTrace = stackTrace,
        settings = linkedMapOf("pushIdle" to "true", "fetchPolicy" to "ALWAYS"),
        logs = listOf("line 1", "line 2"),
        userComment = comment,
    )

    @Test
    fun `round-trips through storage json`() {
        val original = sample(comment = "please fix")

        val restored = DebugReport.fromStorageJson(original.toStorageJson())

        assertEquals(original, restored)
    }

    @Test
    fun `round-trips a manual report with no stack trace`() {
        val original = sample(kind = ReportKind.MANUAL, stackTrace = null)

        val restored = DebugReport.fromStorageJson(original.toStorageJson())

        assertEquals(ReportKind.MANUAL, restored.kind)
        assertNull(restored.stackTrace)
        assertEquals(original, restored)
    }

    @Test
    fun `submission payload contains the fields the user should see`() {
        val payload = sample(comment = "it froze").toSubmissionPayload()

        assertTrue(payload.contains("\"kind\": \"CRASH\""), payload)
        assertTrue(payload.contains("0.1.0"))
        assertTrue(payload.contains("Pixel 8"))
        assertTrue(payload.contains("it froze"))
        assertTrue(payload.contains("boom"))
        assertTrue(payload.contains("pushIdle"))
        assertTrue(payload.contains("line 1"))
    }

    @Test
    fun `submission payload reflects the edited comment`() {
        val base = sample()

        val edited = base.copy(userComment = "edited note").toSubmissionPayload()

        assertTrue(edited.contains("edited note"))
    }

    @Test
    fun `round-trips the reply-to email`() {
        val original = sample().copy(userEmail = "reporter@example.com")

        val restored = DebugReport.fromStorageJson(original.toStorageJson())

        assertEquals("reporter@example.com", restored.userEmail)
        assertEquals(original, restored)
    }

    @Test
    fun `a report with no email round-trips to a blank one`() {
        val restored = DebugReport.fromStorageJson(sample().toStorageJson())

        assertEquals("", restored.userEmail)
    }

    @Test
    fun `submission payload contains the reply-to email`() {
        val payload = sample().copy(userEmail = "reporter@example.com").toSubmissionPayload()

        assertTrue(payload.contains("reporter@example.com"))
    }

    @Test
    fun `surfaced flag round-trips through storage json`() {
        val original = sample().copy(surfaced = true)

        val restored = DebugReport.fromStorageJson(original.toStorageJson())

        assertTrue(restored.surfaced)
        assertEquals(original, restored)
    }

    @Test
    fun `a legacy stored report without the surfaced flag reads as not surfaced`() {
        val legacy = JSONObject(sample().toStorageJson()).apply { remove("surfaced") }.toString()

        val restored = DebugReport.fromStorageJson(legacy)

        assertFalse(restored.surfaced)
    }

    @Test
    fun `the internal surfaced flag never appears in the submission payload`() {
        val payload = sample().copy(surfaced = true).toSubmissionPayload()

        assertFalse(payload.contains("surfaced"))
    }
}
