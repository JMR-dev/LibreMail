// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.domain.model

import org.junit.Test
import kotlin.test.assertEquals

/**
 * A picked/received attachment name becomes an on-disk filename and a MIME `Content-Disposition`
 * filename, so it must not carry path separators (directory traversal) or CR/LF/control characters
 * (header injection). [sanitizeAttachmentName] is the single chokepoint the compose picker and the
 * outbox staging both run names through.
 */
class SanitizeAttachmentNameTest {

    @Test
    fun `keeps an ordinary name unchanged`() {
        assertEquals("report.pdf", sanitizeAttachmentName("report.pdf"))
    }

    @Test
    fun `strips CR and LF so a name cannot inject a header line`() {
        assertEquals(
            "invoice.pdfX-Evil: 1",
            sanitizeAttachmentName("invoice.pdf\r\nX-Evil: 1"),
        )
    }

    @Test
    fun `strips other control characters`() {
        // Char(9) = TAB and Char(0) = NUL are ISO control chars — removed, leaving the printable name.
        val tab = Char(9)
        val nul = Char(0)
        assertEquals("ab.png", sanitizeAttachmentName("a" + tab + "b" + nul + ".png"))
    }

    @Test
    fun `drops any path prefix using either separator`() {
        assertEquals("passwd", sanitizeAttachmentName("../../etc/passwd"))
        assertEquals("evil.exe", sanitizeAttachmentName("C:\\Windows\\evil.exe"))
    }

    @Test
    fun `falls back to a default when nothing usable remains`() {
        assertEquals("attachment", sanitizeAttachmentName(""))
        assertEquals("attachment", sanitizeAttachmentName("\r\n"))
        assertEquals("attachment", sanitizeAttachmentName("some/dir/"))
    }
}
