// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.domain.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the pure behaviour and field-carrying contracts of the domain models that the mapper and
 * repository suites don't reach directly: [AccountSettings.signatureBlock]'s three branches,
 * [Signature.plainText], the default-argument constructors ([OutboxMessage]/[Signature]) and the
 * display-name fallbacks in [Account.outlook] / [MailProvider.createAccount].
 */
class DomainModelCoverageTest {

    @Test
    fun `signatureBlock appends an RFC 3676 delimited, right-trimmed block when enabled and non-blank`() {
        val settings = AccountSettings(accountId = "acct", signature = "Ada Lovelace   ", signatureEnabled = true)

        assertEquals("\n\n-- \nAda Lovelace", settings.signatureBlock())
    }

    @Test
    fun `signatureBlock is empty when signatures are disabled`() {
        assertEquals("", AccountSettings("acct", signature = "Ada", signatureEnabled = false).signatureBlock())
    }

    @Test
    fun `signatureBlock is empty when the signature is blank even though it is enabled`() {
        assertEquals("", AccountSettings("acct", signature = "   ", signatureEnabled = true).signatureBlock())
    }

    @Test
    fun `AccountSettings defaults enable signatures and notifications with no retention override`() {
        val settings = AccountSettings("acct")

        assertTrue(settings.signatureEnabled)
        assertTrue(settings.notificationsEnabled)
        assertNull(settings.retentionCount)
        assertNull(settings.retentionMonths)
        assertEquals("", settings.signature)
    }

    @Test
    fun `Signature exposes its fields, defaults to non-default, and converts its html to plain text`() {
        val sig = Signature(id = "acct:sig", accountId = "acct", name = "Work", html = "<p>Regards, <b>Ada</b></p>")

        assertEquals("acct:sig", sig.id)
        assertEquals("acct", sig.accountId)
        assertEquals("Work", sig.name)
        assertEquals("<p>Regards, <b>Ada</b></p>", sig.html)
        assertFalse(sig.isDefault)
        assertTrue(sig.plainText().contains("Regards"))
    }

    @Test
    fun `Signature can be flagged as the account default`() {
        assertTrue(Signature("i", "acct", "Primary", "<p>x</p>", isDefault = true).isDefault)
    }

    @Test
    fun `OutboxMessage defaults its html body to null`() {
        val msg =
            OutboxMessage(id = "o", to = "bob@example.org", subject = "s", body = "b", createdAt = 1L, lastError = null)

        assertEquals("o", msg.id)
        assertEquals("bob@example.org", msg.to)
        assertEquals("s", msg.subject)
        assertEquals("b", msg.body)
        assertEquals(1L, msg.createdAt)
        assertNull(msg.lastError)
        assertNull(msg.bodyHtml)
    }

    @Test
    fun `OutboxMessage carries an html body and last error when supplied`() {
        val msg = OutboxMessage("o", "bob@example.org", "s", "b", 1L, "smtp 550", "<p>b</p>")

        assertEquals("smtp 550", msg.lastError)
        assertEquals("<p>b</p>", msg.bodyHtml)
    }

    @Test
    fun `Account outlook falls back to the email when the display name is blank`() {
        val account = Account.outlook("me@outlook.com", displayName = "")

        assertEquals("me@outlook.com", account.displayName)
        assertEquals("outlook:me@outlook.com", account.id)
        assertEquals("me@outlook.com", account.email)
        assertEquals(AuthType.OAUTH_OUTLOOK, account.authType)
        assertEquals("outlook.office365.com", account.imap.host)
        assertEquals("smtp.office365.com", account.smtp.host)
    }

    @Test
    fun `Account outlook keeps an explicit non-blank display name`() {
        assertEquals("Work", Account.outlook("me@outlook.com", "Work").displayName)
    }

    @Test
    fun `MailProvider createAccount falls back to the trimmed email when the display name is blank`() {
        val account = MailProvider.GMAIL.createAccount("user@gmail.com", displayName = "   ")

        assertEquals("user@gmail.com", account.displayName)
    }

    @Test
    fun `Message carries every field it is constructed with`() {
        val message = Message(
            id = "acct:INBOX:1",
            accountId = "acct",
            sender = "Ada",
            senderEmail = "ada@example.org",
            subject = "Hi",
            snippet = "snip",
            body = "body",
            isHtml = true,
            timestampMillis = 5L,
            isRead = true,
            isStarred = true,
            folder = "INBOX",
            inInbox = true,
            bodyFetched = true,
        )

        assertEquals("acct:INBOX:1", message.id)
        assertEquals("acct", message.accountId)
        assertEquals("Ada", message.sender)
        assertEquals("ada@example.org", message.senderEmail)
        assertEquals("Hi", message.subject)
        assertEquals("snip", message.snippet)
        assertEquals("body", message.body)
        assertTrue(message.isHtml)
        assertEquals(5L, message.timestampMillis)
        assertTrue(message.isRead)
        assertTrue(message.isStarred)
        assertEquals("INBOX", message.folder)
        assertTrue(message.inInbox)
        assertTrue(message.bodyFetched)
    }

    @Test
    fun `Folder carries every field including the parsed hierarchy delimiter`() {
        val folder = Folder(
            accountId = "acct",
            fullName = "[Gmail]/Sent Mail",
            displayName = "Sent Mail",
            role = FolderRole.SENT,
            selectable = true,
            specialUse = true,
            hierarchyDelimiter = '/',
        )

        assertEquals("acct", folder.accountId)
        assertEquals("[Gmail]/Sent Mail", folder.fullName)
        assertEquals("Sent Mail", folder.displayName)
        assertEquals(FolderRole.SENT, folder.role)
        assertTrue(folder.selectable)
        assertTrue(folder.specialUse)
        assertEquals('/', folder.hierarchyDelimiter)
    }
}
