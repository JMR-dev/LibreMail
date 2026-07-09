// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local

import org.junit.Test
import org.libremail.data.local.entity.AccountEntity
import org.libremail.data.local.entity.AccountSettingsEntity
import org.libremail.data.local.entity.AttachmentEntity
import org.libremail.data.local.entity.FolderUnreadCount
import org.libremail.data.local.entity.MessageEntity
import org.libremail.data.local.entity.MessageSummary
import org.libremail.data.local.entity.OutboxEntity
import org.libremail.data.local.entity.ServerConfigEmbedded
import org.libremail.domain.model.Account
import org.libremail.domain.model.AccountSettings
import org.libremail.domain.model.AuthType
import org.libremail.domain.model.MailSecurity
import org.libremail.domain.model.ServerConfig
import org.libremail.mail.AttachmentPart
import org.libremail.mail.FetchedMessage
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round-trips the entity<->domain mappers in `Mappers.kt` that the higher-level repository tests don't
 * already exercise field-by-field: the account/settings/message/attachment/outbox mappers plus the
 * connection-param builders. Pins the persisted-enum fallbacks (an unknown `authType`/`security` name
 * must degrade to a safe default rather than throw) and the composite-id / uid-parse rules that
 * [FetchedMessage.toEntity] encodes.
 */
class MappersTest {

    @Test
    fun `Account round-trips through the entity, preserving auth type and both server configs`() {
        val account = Account(
            id = "outlook:me@outlook.com",
            email = "me@outlook.com",
            displayName = "Me",
            authType = AuthType.OAUTH_OUTLOOK,
            imap = ServerConfig("outlook.office365.com", 993, MailSecurity.SSL_TLS),
            smtp = ServerConfig("smtp.office365.com", 587, MailSecurity.STARTTLS),
        )

        assertEquals(account, account.toEntity().toDomain())
    }

    @Test
    fun `Account authError round-trips through the entity in both directions (issue 362)`() {
        val healthy = Account(
            id = "acct",
            email = "ada@example.org",
            displayName = "Ada",
            authType = AuthType.PASSWORD_IMAP,
            imap = ServerConfig("imap.example.org", 993, MailSecurity.SSL_TLS),
            smtp = ServerConfig("smtp.example.org", 465, MailSecurity.SSL_TLS),
        )
        assertNull(healthy.toEntity().authError, "a healthy account carries no persisted error")
        assertNull(healthy.toEntity().toDomain().authError)

        val errored = healthy.copy(authError = "Please remove and re-add this account with valid credentials")
        assertEquals(errored.authError, errored.toEntity().authError, "the error is persisted")
        assertEquals(errored, errored.toEntity().toDomain(), "and reads back intact")
    }

    @Test
    fun `AccountEntity toDomain falls back to safe defaults for unknown persisted enum names`() {
        val entity = AccountEntity(
            id = "acct",
            email = "ada@example.org",
            displayName = "Ada",
            authType = "SOME_FUTURE_AUTH",
            imap = ServerConfigEmbedded("imap.example.org", 993, "MYSTERY_SECURITY"),
            smtp = ServerConfigEmbedded("smtp.example.org", 465, "SSL_TLS"),
        )

        val account = entity.toDomain()

        // An unrecognized authType degrades to PASSWORD_IMAP; an unrecognized security to SSL_TLS.
        assertEquals(AuthType.PASSWORD_IMAP, account.authType)
        assertEquals(MailSecurity.SSL_TLS, account.imap.security)
        assertEquals(MailSecurity.SSL_TLS, account.smtp.security)
    }

    @Test
    fun `Account toImapParams carries the imap config and auth options with strict STARTTLS by default`() {
        val params = account().toImapParams(secret = "s3cret", useXoauth2 = true)

        assertEquals("imap.example.org", params.host)
        assertEquals(993, params.port)
        assertEquals(MailSecurity.SSL_TLS, params.security)
        assertEquals("ada@example.org", params.username)
        assertEquals("s3cret", params.secret)
        assertTrue(params.useXoauth2)
        assertTrue(params.strictStartTls)
    }

    @Test
    fun `Account toImapParams honors an explicit non-strict STARTTLS flag`() {
        assertFalse(account().toImapParams(secret = "s", useXoauth2 = false, strictStartTls = false).strictStartTls)
    }

    @Test
    fun `Account toSmtpParams carries the smtp config and auth options with strict STARTTLS by default`() {
        val params = account().toSmtpParams(secret = "token", useXoauth2 = true)

        assertEquals("smtp.example.org", params.host)
        assertEquals(587, params.port)
        assertEquals(MailSecurity.STARTTLS, params.security)
        assertEquals("ada@example.org", params.username)
        assertEquals("token", params.secret)
        assertTrue(params.useXoauth2)
        assertTrue(params.strictStartTls)
    }

    @Test
    fun `Account toSmtpParams honors an explicit non-strict STARTTLS flag`() {
        assertFalse(account().toSmtpParams(secret = "s", useXoauth2 = false, strictStartTls = false).strictStartTls)
    }

    @Test
    fun `AccountSettings round-trips through the entity including retention overrides`() {
        val settings = AccountSettings(
            accountId = "acct",
            signature = "Regards, Ada",
            signatureEnabled = false,
            notificationsEnabled = false,
            retentionCount = 500,
            retentionMonths = 12,
        )

        assertEquals(settings, settings.toEntity().toDomain())
    }

    @Test
    fun `AccountSettingsEntity toDomain keeps null retention overrides and default toggles`() {
        val domain = AccountSettingsEntity("acct").toDomain()

        assertEquals("acct", domain.accountId)
        assertEquals("", domain.signature)
        assertTrue(domain.signatureEnabled)
        assertTrue(domain.notificationsEnabled)
        assertNull(domain.retentionCount)
        assertNull(domain.retentionMonths)
    }

    @Test
    fun `MessageEntity toDomain carries every field including the full body`() {
        val entity = MessageEntity(
            id = "acct:INBOX:1",
            accountId = "acct",
            sender = "Ada",
            senderEmail = "ada@example.org",
            subject = "Hi",
            snippet = "snip",
            body = "Full body",
            isHtml = true,
            timestampMillis = 5L,
            isRead = true,
            isStarred = true,
            folder = "INBOX",
            inInbox = true,
            bodyFetched = true,
            uid = 1L,
        )

        val message = entity.toDomain()

        assertEquals("acct:INBOX:1", message.id)
        assertEquals("Full body", message.body)
        assertTrue(message.isHtml)
        assertTrue(message.isRead)
        assertTrue(message.isStarred)
        assertTrue(message.bodyFetched)
        assertEquals("snip", message.snippet)
        assertEquals(5L, message.timestampMillis)
    }

    @Test
    fun `MessageSummary toDomain leaves the body empty because the list never renders it`() {
        val summary = MessageSummary(
            id = "acct:INBOX:2",
            accountId = "acct",
            sender = "Ada",
            senderEmail = "ada@example.org",
            subject = "Hi",
            snippet = "snip",
            timestampMillis = 5L,
            isRead = false,
            isStarred = false,
            folder = "INBOX",
            inInbox = true,
            bodyFetched = false,
        )

        val message = summary.toDomain()

        assertEquals("", message.body)
        assertFalse(message.isHtml)
        assertEquals("acct:INBOX:2", message.id)
        assertEquals("snip", message.snippet)
    }

    @Test
    fun `FetchedMessage toEntity builds a composite id and defaults the cached-body fields`() {
        val fetched = FetchedMessage(
            uid = "42",
            sender = "Ada",
            senderEmail = "ada@example.org",
            subject = "Hi",
            timestampMillis = 1_000L,
            isRead = true,
            isFlagged = true,
        )

        val entity = fetched.toEntity(accountId = "acct", folder = "INBOX")

        assertEquals("acct:INBOX:42", entity.id)
        assertEquals(42L, entity.uid)
        assertEquals("acct", entity.accountId)
        assertEquals("INBOX", entity.folder)
        assertTrue(entity.isRead)
        // The server \Flagged flag maps onto the local starred column.
        assertTrue(entity.isStarred)
        // A freshly-listed header defaults to in-inbox with no cached body yet.
        assertTrue(entity.inInbox)
        assertFalse(entity.bodyFetched)
        assertEquals("", entity.snippet)
        assertEquals("", entity.body)
        assertFalse(entity.isHtml)
    }

    @Test
    fun `FetchedMessage toEntity marks a search hit not-in-inbox and zeroes an unparseable uid`() {
        val fetched = FetchedMessage(
            uid = "not-a-number",
            sender = "Ada",
            senderEmail = "ada@example.org",
            subject = "Hi",
            timestampMillis = 0L,
            isRead = false,
            isFlagged = false,
        )

        val entity = fetched.toEntity(accountId = "acct", folder = "INBOX", inInbox = false)

        assertEquals("acct:INBOX:not-a-number", entity.id)
        // A non-numeric uid can't be materialized, so it stores 0 (refreshed on the next sync).
        assertEquals(0L, entity.uid)
        assertFalse(entity.inInbox)
    }

    @Test
    fun `FolderUnreadCount toDomain maps the aggregate row to the domain unread count`() {
        val domain = FolderUnreadCount(accountId = "acct", folder = "INBOX", unreadCount = 7).toDomain()

        assertEquals("acct", domain.accountId)
        assertEquals("INBOX", domain.folder)
        assertEquals(7, domain.count)
    }

    @Test
    fun `AttachmentEntity toDomain carries the content id for an inline part`() {
        val domain = AttachmentEntity("m", 2, "logo.png", "image/png", 42L, contentId = "logo@cid").toDomain()

        assertEquals("m", domain.messageId)
        assertEquals(2, domain.partIndex)
        assertEquals("logo.png", domain.filename)
        assertEquals("image/png", domain.mimeType)
        assertEquals(42L, domain.sizeBytes)
        assertEquals("logo@cid", domain.contentId)
    }

    @Test
    fun `AttachmentEntity toDomain leaves an ordinary attachment content id null`() {
        assertNull(AttachmentEntity("m", 0, "a.pdf", "application/pdf", 1L).toDomain().contentId)
    }

    @Test
    fun `AttachmentPart toEntity attaches the part metadata to its message`() {
        val entity = AttachmentPart(3, "cat.png", "image/png", 9L, contentId = "cat@cid").toEntity("msg-1")

        assertEquals("msg-1", entity.messageId)
        assertEquals(3, entity.partIndex)
        assertEquals("cat.png", entity.filename)
        assertEquals("image/png", entity.mimeType)
        assertEquals(9L, entity.sizeBytes)
        assertEquals("cat@cid", entity.contentId)
    }

    @Test
    fun `OutboxEntity toDomain exposes the queue fields, last error, and html body`() {
        val entity = OutboxEntity(
            id = "o1",
            accountId = "acct",
            toAddresses = "bob@example.org",
            ccAddresses = "",
            subject = "Hi",
            body = "hello",
            createdAt = 3L,
            lastError = "smtp 550",
            bodyHtml = "<p>hello</p>",
        )

        val domain = entity.toDomain()

        assertEquals("o1", domain.id)
        assertEquals("bob@example.org", domain.to)
        assertEquals("Hi", domain.subject)
        assertEquals("hello", domain.body)
        assertEquals(3L, domain.createdAt)
        assertEquals("smtp 550", domain.lastError)
        assertEquals("<p>hello</p>", domain.bodyHtml)
    }

    @Test
    fun `malformed or blank attachment json decodes to an empty list`() {
        // Blank short-circuits; unparseable JSON is caught and defaulted rather than thrown.
        assertEquals(emptyList(), "   ".toOutgoingAttachments())
        assertEquals(emptyList(), "not valid json".toOutgoingAttachments())
    }

    private fun account() = Account(
        id = "imap:ada@example.org",
        email = "ada@example.org",
        displayName = "Ada",
        authType = AuthType.PASSWORD_IMAP,
        imap = ServerConfig("imap.example.org", 993, MailSecurity.SSL_TLS),
        smtp = ServerConfig("smtp.example.org", 587, MailSecurity.STARTTLS),
    )
}
