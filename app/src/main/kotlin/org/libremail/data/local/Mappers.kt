// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local

import org.libremail.data.local.entity.AccountEntity
import org.libremail.data.local.entity.MessageEntity
import org.libremail.data.local.entity.ServerConfigEmbedded
import org.libremail.domain.model.Account
import org.libremail.domain.model.AuthType
import org.libremail.domain.model.ImapConnectionParams
import org.libremail.domain.model.MailSecurity
import org.libremail.domain.model.Message
import org.libremail.domain.model.ServerConfig
import org.libremail.domain.model.SmtpParams
import org.libremail.mail.FetchedMessage

internal fun AccountEntity.toDomain(): Account = Account(
    id = id,
    email = email,
    displayName = displayName,
    authType = runCatching { AuthType.valueOf(authType) }.getOrDefault(AuthType.PASSWORD_IMAP),
    imap = ServerConfig(imap.host, imap.port, imap.security.toMailSecurity()),
    smtp = ServerConfig(smtp.host, smtp.port, smtp.security.toMailSecurity()),
)

internal fun Account.toEntity(): AccountEntity = AccountEntity(
    id = id,
    email = email,
    displayName = displayName,
    authType = authType.name,
    imap = ServerConfigEmbedded(imap.host, imap.port, imap.security.name),
    smtp = ServerConfigEmbedded(smtp.host, smtp.port, smtp.security.name),
)

internal fun Account.toImapParams(secret: String, useXoauth2: Boolean): ImapConnectionParams =
    ImapConnectionParams(
        host = imap.host,
        port = imap.port,
        security = imap.security,
        username = email,
        secret = secret,
        useXoauth2 = useXoauth2,
    )

internal fun Account.toSmtpParams(secret: String, useXoauth2: Boolean): SmtpParams =
    SmtpParams(
        host = smtp.host,
        port = smtp.port,
        security = smtp.security,
        username = email,
        secret = secret,
        useXoauth2 = useXoauth2,
    )

internal fun MessageEntity.toDomain(): Message = Message(
    id = id,
    accountId = accountId,
    sender = sender,
    senderEmail = senderEmail,
    subject = subject,
    snippet = snippet,
    body = body,
    isHtml = isHtml,
    timestampMillis = timestampMillis,
    isRead = isRead,
    isStarred = isStarred,
)

internal fun FetchedMessage.toEntity(accountId: String): MessageEntity = MessageEntity(
    id = "$accountId:$uid",
    accountId = accountId,
    sender = sender,
    senderEmail = senderEmail,
    subject = subject,
    snippet = "",
    body = "",
    isHtml = false,
    timestampMillis = timestampMillis,
    isRead = isRead,
    isStarred = isFlagged,
)

private fun String.toMailSecurity(): MailSecurity =
    runCatching { MailSecurity.valueOf(this) }.getOrDefault(MailSecurity.SSL_TLS)
