// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local

import org.json.JSONArray
import org.json.JSONObject
import org.libremail.data.local.entity.AccountEntity
import org.libremail.data.local.entity.AccountSettingsEntity
import org.libremail.data.local.entity.AttachmentEntity
import org.libremail.data.local.entity.DraftEntity
import org.libremail.data.local.entity.FolderEntity
import org.libremail.data.local.entity.MessageEntity
import org.libremail.data.local.entity.MessageSummary
import org.libremail.data.local.entity.OutboxEntity
import org.libremail.data.local.entity.ServerConfigEmbedded
import org.libremail.domain.model.Account
import org.libremail.domain.model.AccountSettings
import org.libremail.domain.model.Attachment
import org.libremail.domain.model.AuthType
import org.libremail.domain.model.Draft
import org.libremail.domain.model.Folder
import org.libremail.domain.model.FolderRole
import org.libremail.domain.model.ImapConnectionParams
import org.libremail.domain.model.MailSecurity
import org.libremail.domain.model.Message
import org.libremail.domain.model.OutboxMessage
import org.libremail.domain.model.OutgoingAttachment
import org.libremail.domain.model.ServerConfig
import org.libremail.domain.model.SmtpParams
import org.libremail.mail.AttachmentPart
import org.libremail.mail.FetchedFolder
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

internal fun AccountSettingsEntity.toDomain(): AccountSettings = AccountSettings(
    accountId = accountId,
    signature = signature,
    signatureEnabled = signatureEnabled,
    notificationsEnabled = notificationsEnabled,
    retentionCount = retentionCount,
    retentionMonths = retentionMonths,
)

internal fun AccountSettings.toEntity(): AccountSettingsEntity = AccountSettingsEntity(
    accountId = accountId,
    signature = signature,
    signatureEnabled = signatureEnabled,
    notificationsEnabled = notificationsEnabled,
    retentionCount = retentionCount,
    retentionMonths = retentionMonths,
)

internal fun Account.toImapParams(
    secret: String,
    useXoauth2: Boolean,
    strictStartTls: Boolean = true,
): ImapConnectionParams = ImapConnectionParams(
    host = imap.host,
    port = imap.port,
    security = imap.security,
    username = email,
    secret = secret,
    useXoauth2 = useXoauth2,
    strictStartTls = strictStartTls,
)

internal fun Account.toSmtpParams(secret: String, useXoauth2: Boolean, strictStartTls: Boolean = true): SmtpParams =
    SmtpParams(
        host = smtp.host,
        port = smtp.port,
        security = smtp.security,
        username = email,
        secret = secret,
        useXoauth2 = useXoauth2,
        strictStartTls = strictStartTls,
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
    folder = folder,
    inInbox = inInbox,
    bodyFetched = bodyFetched,
)

/**
 * Maps a mailbox-list projection to the domain model. [Message.body]/[Message.isHtml] are left
 * empty because the list never renders them — the reader loads the body on demand (see
 * [MessageSummary]).
 */
internal fun MessageSummary.toDomain(): Message = Message(
    id = id,
    accountId = accountId,
    sender = sender,
    senderEmail = senderEmail,
    subject = subject,
    snippet = snippet,
    body = "",
    isHtml = false,
    timestampMillis = timestampMillis,
    isRead = isRead,
    isStarred = isStarred,
    folder = folder,
    inInbox = inInbox,
    bodyFetched = bodyFetched,
)

internal fun FetchedMessage.toEntity(accountId: String, folder: String, inInbox: Boolean = true): MessageEntity =
    MessageEntity(
        id = "$accountId:$folder:$uid",
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
        folder = folder,
        inInbox = inInbox,
        bodyFetched = false,
        uid = uid.toLongOrNull() ?: 0L,
    )

internal fun FolderEntity.toDomain(): Folder = Folder(
    accountId = accountId,
    fullName = fullName,
    displayName = displayName,
    role = runCatching { FolderRole.valueOf(role) }.getOrDefault(FolderRole.NORMAL),
    selectable = selectable,
    specialUse = specialUse,
)

internal fun FetchedFolder.toEntity(accountId: String, sortOrder: Int): FolderEntity = FolderEntity(
    accountId = accountId,
    fullName = fullName,
    displayName = displayName,
    role = FolderRole.roleOf(fullName, displayName, attributes).name,
    selectable = selectable,
    sortOrder = sortOrder,
    specialUse = FolderRole.isServerSpecial(attributes),
)

internal fun AttachmentEntity.toDomain(): Attachment = Attachment(
    messageId = messageId,
    partIndex = partIndex,
    filename = filename,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
)

internal fun AttachmentPart.toEntity(messageId: String): AttachmentEntity = AttachmentEntity(
    messageId = messageId,
    partIndex = partIndex,
    filename = filename,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
)

internal fun DraftEntity.toDomain(): Draft = Draft(
    id = id,
    accountId = accountId,
    to = toAddresses,
    cc = ccAddresses,
    bcc = bccAddresses,
    subject = subject,
    body = body,
    updatedAt = updatedAt,
    bodyHtml = bodyHtml,
    attachments = attachments.toOutgoingAttachments(),
)

internal fun Draft.toEntity(): DraftEntity = DraftEntity(
    id = id,
    accountId = accountId,
    toAddresses = to,
    ccAddresses = cc,
    bccAddresses = bcc,
    subject = subject,
    body = body,
    updatedAt = updatedAt,
    attachments = attachments.toJson(),
    bodyHtml = bodyHtml,
)

/** Serializes draft attachments as a JSON array of {uri, name} objects ("" when empty). */
private fun List<OutgoingAttachment>.toJson(): String {
    if (isEmpty()) return ""
    val array = JSONArray()
    forEach { array.put(JSONObject().put("uri", it.uri).put("name", it.name)) }
    return array.toString()
}

private fun String.toOutgoingAttachments(): List<OutgoingAttachment> {
    if (isBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(this)
        (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            OutgoingAttachment(obj.getString("uri"), obj.optString("name"))
        }
    }.getOrDefault(emptyList())
}

internal fun OutboxEntity.toDomain(): OutboxMessage = OutboxMessage(
    id = id,
    to = toAddresses,
    subject = subject,
    body = body,
    createdAt = createdAt,
    lastError = lastError,
    bodyHtml = bodyHtml,
)

private fun String.toMailSecurity(): MailSecurity = runCatching {
    MailSecurity.valueOf(this)
}.getOrDefault(MailSecurity.SSL_TLS)
