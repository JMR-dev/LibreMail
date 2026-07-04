// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.reporting

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StackTraceScrubberTest {

    @Test
    fun `drops host, ip, port and email from a connect-exception message but keeps classes and frames`() {
        val raw = listOf(
            "java.net.ConnectException: Failed to connect to imap.example.com/93.184.216.34:993 for user@example.com",
            "\tat org.libremail.mail.ImapClient.connect(ImapClient.kt:42)",
            "\tat org.libremail.mail.ImapClient.open(ImapClient.kt:17)",
            "Caused by: java.net.SocketException: Broken pipe to smtp.example.com:587",
            "\tat java.base/sun.nio.ch.Net.connect(Net.java:579)",
            "\t... 12 more",
        ).joinToString("\n")

        val scrubbed = StackTraceScrubber.scrub(raw)

        // PII gone: hostnames, host:port tokens, the IP address and the email.
        assertFalse(scrubbed.contains("imap.example.com"), scrubbed)
        assertFalse(scrubbed.contains("smtp.example.com"), scrubbed)
        assertFalse(scrubbed.contains("93.184.216.34"), scrubbed)
        assertFalse(scrubbed.contains(":993"), scrubbed)
        assertFalse(scrubbed.contains(":587"), scrubbed)
        assertFalse(scrubbed.contains("user@example.com"), scrubbed)
        assertFalse(scrubbed.contains("example"), scrubbed)

        // Useful non-PII structure kept: exception class names and every frame (class/method/file/line).
        assertTrue(scrubbed.contains("java.net.ConnectException"), scrubbed)
        assertTrue(scrubbed.contains("Caused by: java.net.SocketException"), scrubbed)
        assertTrue(scrubbed.contains("at org.libremail.mail.ImapClient.connect(ImapClient.kt:42)"), scrubbed)
        // A frame's own File.java:line is preserved — it must not be mistaken for a host:port.
        assertTrue(scrubbed.contains("at java.base/sun.nio.ch.Net.connect(Net.java:579)"), scrubbed)
        assertTrue(scrubbed.contains("... 12 more"), scrubbed)
    }

    @Test
    fun `redacts an email and host-port left on a non-header continuation line`() {
        // Some mail libraries append detail on a continuation line with no "Type:" prefix, so the
        // message-drop can't strip it structurally; the regex redaction must still catch the PII.
        val raw = listOf(
            "javax.mail.AuthenticationFailedException: LOGIN failed",
            "\trejected user@example.org at imap.mail.example.org:143",
            "\tat org.libremail.mail.ImapClient.login(ImapClient.kt:88)",
        ).joinToString("\n")

        val scrubbed = StackTraceScrubber.scrub(raw)

        assertFalse(scrubbed.contains("user@example.org"), scrubbed)
        assertFalse(scrubbed.contains("imap.mail.example.org"), scrubbed)
        assertFalse(scrubbed.contains(":143"), scrubbed)
        assertFalse(scrubbed.contains("example"), scrubbed)
        assertTrue(scrubbed.contains("[redacted]"), scrubbed)
        assertTrue(scrubbed.contains("javax.mail.AuthenticationFailedException"), scrubbed)
        assertTrue(scrubbed.contains("at org.libremail.mail.ImapClient.login(ImapClient.kt:88)"), scrubbed)
    }

    @Test
    fun `keeps a null-message exception and its frames verbatim`() {
        // The common app-logic crash (no PII, no message) must survive untouched.
        val raw = listOf(
            "java.lang.NullPointerException",
            "\tat org.libremail.MainActivity.onCreate(MainActivity.kt:10)",
        ).joinToString("\n")

        assertEquals(raw, StackTraceScrubber.scrub(raw))
    }
}
