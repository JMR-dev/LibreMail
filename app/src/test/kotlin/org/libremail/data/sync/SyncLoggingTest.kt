// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import org.junit.Test
import kotlin.test.assertEquals

/**
 * [logSafeFolderLabel] is the PII gate between a raw IMAP folder path and an [org.libremail.reporting.AppLog]
 * breadcrumb (issue #329's folder-name caveat): a known system folder logs by name, anything else logs
 * as the fixed placeholder — never the real name, regardless of nesting or delimiter.
 */
class SyncLoggingTest {

    @Test
    fun `a top-level system folder logs verbatim`() {
        assertEquals("INBOX", logSafeFolderLabel("INBOX"))
        assertEquals("Sent", logSafeFolderLabel("Sent"))
        assertEquals("Drafts", logSafeFolderLabel("Drafts"))
        assertEquals("Trash", logSafeFolderLabel("Trash"))
        assertEquals("Archive", logSafeFolderLabel("Archive"))
    }

    @Test
    fun `matching is case-insensitive but preserves the server's own casing`() {
        assertEquals("JUNK", logSafeFolderLabel("JUNK"))
        assertEquals("sent items", logSafeFolderLabel("sent items"))
    }

    @Test
    fun `a system folder nested under a Gmail-style slash path logs only its leaf name`() {
        assertEquals("Sent Mail", logSafeFolderLabel("[Gmail]/Sent Mail"))
        assertEquals("All Mail", logSafeFolderLabel("[Gmail]/All Mail"))
    }

    @Test
    fun `a system folder nested under a Dovecot-style dot path logs only its leaf name`() {
        assertEquals("Trash", logSafeFolderLabel("INBOX.Trash"))
    }

    @Test
    fun `a user-created top-level folder never logs its real name`() {
        assertEquals("<folder>", logSafeFolderLabel("Projects"))
        assertEquals("<folder>", logSafeFolderLabel("Invoice from Acme Corp"))
    }

    @Test
    fun `a user-created nested folder logs the placeholder, never its name or its parent`() {
        assertEquals("<folder>", logSafeFolderLabel("Work/Reports"))
        assertEquals("<folder>", logSafeFolderLabel("Clients/Acme Corp"))
    }

    @Test
    fun `a folder that merely shares a system folder's leaf name still logs only that leaf`() {
        // Even a custom parent is never exposed — only the recognized leaf is ever logged, by design.
        assertEquals("Archive", logSafeFolderLabel("Clients/Archive"))
    }
}
